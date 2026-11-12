package org.xianshen.mumirrorb.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.service.RecordDraftService;
import org.xianshen.mumirrorb.service.RecordService;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.xianshen.mumirrorb.action.RecordActionHandler.*;

/** RecordActionExecution keeps the service entry points and transaction boundaries in RecordActionHandler. */
final class RecordActionExecution {
    private final RecordActionHandler owner;
    RecordActionExecution(RecordActionHandler owner) { this.owner=owner; }



    @Transactional
    public ActionExecution execute(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        if ("record.delete".equals(action.getKind())) {
            Record target = owner.ownedSource(action.getUserId(), payload.path("recordId").asLong());
            if (target == null) throw new BusinessException(404, "记录不存在或已删除");
            String expected = action.getTargetVersions().path(String.valueOf(target.getId())).asText(null);
            if (expected == null || !expected.equals(owner.targetVersion(target))) throw new BusinessException(409, "记录已变化，请重新准备删除卡");
            owner.records.softDelete(target.getId(), action.getUserId());
            return ActionExecution.applied(owner.json.createObjectNode().put("recordId", target.getId()).put("deleted", true));
        }
        RecordDraftDTO draft = owner.json.convertValue(payload.path("recordDraft"), RecordDraftDTO.class);
        if ("AI_ASSISTED".equals(draft.getMode()) && !draft.getGrowthSuggestions().isEmpty() && owner.growthService != null
                && owner.growthService.axesVersion(action.getUserId()) != draft.getAxesVersion())
            throw new BusinessException(409, "成长维度已变化，请重新准备审核卡");
        owner.normalizeSingleFinalContent(draft);
        Long sourceId = "record.organize".equals(action.getKind()) ? draft.getSourceRecordId() : null;
        Long correctionOfId = payload.path("correctionOfRecordId").isIntegralNumber()
                ? payload.path("correctionOfRecordId").asLong() : null;
        Record correction = correctionOfId == null ? null : owner.lockCorrectionSource(action.getUserId(), correctionOfId);
        if (correction != null) {
            String expectedCorrectionVersion = action.getTargetVersions()
                    .path(String.valueOf(correction.getId())).asText(null);
            if (expectedCorrectionVersion == null || !expectedCorrectionVersion.equals(owner.targetVersion(correction)))
                throw new BusinessException(409, "更正来源已变化，请重新准备候选");
        }
        if ("ONLY_SAVE".equals(draft.getMode())) {
            if (sourceId != null) {
                ObjectNode unchanged = owner.json.createObjectNode().put("recordId", sourceId).put("savedOnly", true);
                unchanged.put("outcome", "SAVED_ONLY_FALLBACK");
                return ActionExecution.applied(unchanged);
            }
            RecordDTO dto = new RecordDTO();
            dto.setContent(draft.getRawContent());
            dto.setCorrectionOfRecordId(correctionOfId);
            var created = owner.records.create(dto, action.getUserId());
            ObjectNode saved = owner.json.createObjectNode().put("recordId", created.getId()).put("savedOnly", true);
            saved.put("outcome", "SAVED_ONLY_FALLBACK");
            return ActionExecution.applied(saved);
        }

        Record record;
        if (sourceId == null) {
            RecordDTO dto = new RecordDTO();
            dto.setContent(draft.getRawContent());
            dto.setCorrectionOfRecordId(correctionOfId);
            var created = owner.records.create(dto, action.getUserId());
            record = owner.ownedSource(action.getUserId(), created.getId());
        } else {
            record = owner.recordMapper.selectOwnedForUpdate(action.getUserId(), sourceId);
            if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
            if (!Objects.equals(action.getTargetVersions().path(String.valueOf(sourceId)).asText(), owner.targetVersion(record)))
                throw new BusinessException(409, "记录原文已变化，请重新准备整理候选");
            owner.validateOrganizeSource(action.getUserId(), record);
        }
        record.setAiProcessingAuthorized(true);
        record.setProcessingVersion(Math.max(1, record.getProcessingVersion() == null ? 0 : record.getProcessingVersion() + 1));
        record.setStatus(RecordStatus.REVIEWING);
        try { record.setGrowthSuggestions(owner.json.writeValueAsString(draft.getGrowthSuggestions())); }
        catch (Exception e) { throw new BusinessException(400, "成长建议格式无效"); }
        record.setGrowthAxesVersion((long) draft.getAxesVersion());
        record.setFailReason(null);
        record.setUpdatedAt(OffsetDateTime.now());
        owner.recordMapper.updateById(record);

        List<Long> approvedMemory = new ArrayList<>();
        List<Long> approvedTodos = new ArrayList<>();
        for (RecordDraftDTO.Segment segment : draft.getSegments()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (segment.getTitle() != null) metadata.put("title", segment.getTitle());
            if (segment.getSummary() != null) metadata.put("summary", segment.getSummary());
            if (segment.getContentType() != null) metadata.put("contentType", segment.getContentType());
            metadata.put("mood", segment.getMoods() == null ? List.of() : segment.getMoods());
            metadata.put("keywords", segment.getKeywords() == null ? List.of() : segment.getKeywords());
            metadata.put("userTags", segment.getTags() == null ? List.of() : segment.getTags());
            if (segment.getTaskStatus() != null) metadata.put("taskStatus", segment.getTaskStatus());
            Chunk chunk = Chunk.builder().userId(action.getUserId()).recordId(record.getId())
                    .content(draft.getRawContent()).segment(segment.getText()).classifiedSegment(segment.getText())
                    .metadata(metadata).userEdited(true).aiMemoryApproved(false).todoApproved(false)
                    .contentVersion(1L).embeddingStatus("not_requested").createdAt(OffsetDateTime.now()).build();
            owner.chunkMapper.insert(chunk);
            if (owner.achievementActivity!=null)
                owner.achievementActivity.bindDraftEdit(action.getUserId(),action.getActionId(),segment.getDraftId(),chunk.getId());
            if ("AI_ASSISTED".equals(draft.getMode()) || segment.isMemoryEnabled()) approvedMemory.add(chunk.getId());
            if (segment.isTodoEnabled()) approvedTodos.add(chunk.getId());
        }
        List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO> growthAdjustments;
        try {
            growthAdjustments = draft.getGrowthSuggestions().isEmpty() ? List.of()
                    : owner.json.convertValue(draft.getGrowthSuggestions(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO>>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "成长建议格式无效");
        }
        var confirmed = owner.records.confirmReview(record.getId(), action.getUserId(), approvedMemory, approvedTodos,
                owner.resolutions(payload), action.getActionId(), action.getExecutionVersion(),
                growthAdjustments, (long) draft.getAxesVersion());
        ObjectNode result = owner.json.createObjectNode().put("recordId", record.getId()).put("reviewed", true)
                .put("memorySelections", approvedMemory.size()).put("memoryState", confirmed.getMemoryState().name());
        int confirmedResolutions = (int) owner.resolutions(payload).stream().filter(r -> "confirmed".equalsIgnoreCase(r.getAction())).count();
        result.put("todoResolutionsConfirmed", confirmedResolutions).put("todoEffectsPreserved", true);
        if (correctionOfId != null) result.put("correctionOfRecordId", correctionOfId);
        if (confirmed.getQueuedEmbeddingJobIds() != null && !confirmed.getQueuedEmbeddingJobIds().isEmpty()) {
            result.set("jobIds", owner.json.valueToTree(confirmed.getQueuedEmbeddingJobIds()));
            return ActionExecution.executing(result);
        }
        return ActionExecution.applied(result.put("outcome", "APPLIED"));
    }
}
