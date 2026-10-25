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

/** Record create/organize proposals. Preparation is read-only; all domain writes require AuthorizedAction. */
@Component
@RequiredArgsConstructor
public class RecordActionHandler implements ActionHandler {
    private static final Set<String> KINDS = Set.of("record.create", "record.organize", "record.delete");
    private static final Set<String> ALLOWED_FIELDS = Set.of("rawContent", "mode", "privateInput", "sourceRecordId",
            "sourceRecordVersion", "recordDraft", "todoResolutions", "correctionOfRecordId");
    private final RecordDraftService drafts;
    private final RecordService records;
    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final ObjectMapper json;
    private final org.xianshen.mumirrorb.service.SettingsService settingsService;
    @org.springframework.beans.factory.annotation.Autowired private org.xianshen.mumirrorb.service.GrowthService growthService;

    @Override public Set<String> kinds() { return KINDS; }

    @Override
    public boolean asynchronousPreparation(UUID userId, String kind, JsonNode payload) {
        if (!KINDS.contains(kind) || "record.delete".equals(kind)) return false;
        if (payload.path("privateInput").asBoolean(false) || "ONLY_SAVE".equals(payload.path("mode").asText())) return false;
        if ("AI_ASSISTED".equals(payload.path("mode").asText())) return true;
        return "AI_ASSISTED".equals(settingsService.getSettings(userId).getDefaultRecordMode());
    }

    @Override
    public JsonNode initialPreparationPreview(UUID userId, String kind, JsonNode payload) {
        validatePayload(kind, payload);
        Record source = "record.organize".equals(kind) ? ownedSource(userId, sourceId(payload)) : null;
        validateOrganizeSource(userId, source);
        Record correction = correctionSource(userId, payload);
        RecordDraftDTO draft = draftInput(payload, source);
        if (draft.getMode() == null || draft.getMode().isBlank())
            draft.setMode(source == null ? defaultMode(userId, payload) : "AI_ASSISTED");
        draft.setFinalContent(draft.getRawContent());
        draft.setPreparationStatus("RUNNING");
        draft.setAxesVersion(0);
        draft.setGrowthSuggestions(List.of());
        var segment = new RecordDraftDTO.Segment();
        segment.setDraftId(UUID.randomUUID());
        segment.setText(draft.getRawContent());
        draft.setSegments(List.of(segment));
        ObjectNode preview = preview(source, draft, "RUNNING");
        addCorrectionTarget(preview, correction);
        return preview;
    }

    @Override
    public JsonNode completePreparationPreview(UUID userId, String kind, JsonNode payload) {
        ObjectNode sourcePayload = (ObjectNode) payload.deepCopy();
        sourcePayload.remove("recordDraft");
        return validateAndPreview(userId, kind, sourcePayload);
    }

    @Override
    public JsonNode failedPreparationPreview(UUID userId, String kind, JsonNode payload, Exception error) {
        ObjectNode sourcePayload = (ObjectNode) payload.deepCopy();
        sourcePayload.remove("recordDraft");
        sourcePayload.put("mode", "ONLY_SAVE");
        Record source = "record.organize".equals(kind) ? ownedSource(userId, sourceId(sourcePayload)) : null;
        validateOrganizeSource(userId, source);
        Record correction = correctionSource(userId, sourcePayload);
        RecordDraftDTO fallback = draftInput(sourcePayload, source);
        fallback.setMode("ONLY_SAVE");
        fallback.setFinalContent(fallback.getRawContent());
        fallback.setPreparationStatus("SAVE_ONLY");
        fallback.setPreparationError("AI 分类失败，已降级为仅保存候选");
        fallback.setAxesVersion(0);
        fallback.setGrowthSuggestions(List.of());
        var segment = new RecordDraftDTO.Segment();
        segment.setDraftId(UUID.randomUUID());
        segment.setText(fallback.getRawContent());
        fallback.setSegments(List.of(segment));
        ObjectNode preview = preview(source, fallback, "READY");
        addCorrectionTarget(preview, correction);
        return preview;
    }

    @Override
    public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        if ("record.delete".equals(kind)) return deletePreview(userId, payload);
        validatePayload(kind, payload);
        Record source = "record.organize".equals(kind) ? ownedSource(userId, sourceId(payload)) : null;
        validateOrganizeSource(userId, source);
        Record correction = correctionSource(userId, payload);
        if (payload.has("recordDraft")) validateDraftShape(payload.get("recordDraft"));
        RecordDraftDTO draft = payload.has("recordDraft")
                ? json.convertValue(payload.get("recordDraft"), RecordDraftDTO.class)
                : drafts.prepare(userId, draftInput(payload, source));
        if (source != null && !Objects.equals(source.getContent(), draft.getRawContent()))
            throw invalid("整理候选原文与来源记录不一致");
        draft.setSourceRecordId(source == null ? null : source.getId());
        draft.setSourceRecordVersion(source == null ? null : version(source));
        normalizeSingleFinalContent(draft);
        ObjectNode preview = preview(source, draft, draft.getPreparationStatus());
        addCorrectionTarget(preview, correction);
        return preview;
    }

    @Override
    public JsonNode persistedPayload(String kind, JsonNode payload, JsonNode preview) {
        if ("record.delete".equals(kind)) return payload.deepCopy();
        ObjectNode saved = payload.deepCopy();
        saved.set("recordDraft", preview.path("recordDraft").deepCopy());
        if ("AI_ASSISTED".equals(saved.path("recordDraft").path("mode").asText())
                && saved.path("recordDraft").path("segments").isArray()) {
            for (JsonNode segment : saved.path("recordDraft").path("segments")) {
                if (segment.isObject()) ((ObjectNode) segment).put("memoryEnabled", true);
            }
        }
        return saved;
    }

    @Override
    public void validateEdit(String kind, JsonNode originalPayload, JsonNode editedPayload) {
        JsonNode originalDraft = originalPayload.path("recordDraft");
        boolean wasPrivate = originalPayload.path("privateInput").asBoolean(false)
                || originalDraft.path("privateInput").asBoolean(false);
        JsonNode editedDraft = editedPayload.path("recordDraft");
        boolean remainsPrivate = editedPayload.path("privateInput").asBoolean(false) || editedDraft.path("privateInput").asBoolean(false);
        if (wasPrivate && (!remainsPrivate || !"ONLY_SAVE".equals(editedDraft.path("mode").asText())))
            throw invalid("私有输入不能改为 AI 整理或记忆授权");
    }

    @Override
    public void validatePreparation(String kind, JsonNode payload, JsonNode preview) {
        if ("record.delete".equals(kind)) return;
        RecordDraftDTO draft = json.convertValue(payload.path("recordDraft"), RecordDraftDTO.class);
        validateDraft(draft);
        for (RecordDraftDTO.Segment segment : draft.getSegments()) {
            if (segment.getText() == null || segment.getText().isBlank() || segment.getText().length() > 2000)
                throw invalid("片段内容不能为空且不能超过 2000 字");
        }
    }

    @Override
    @Transactional
    public ActionExecution execute(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        if ("record.delete".equals(action.getKind())) {
            Record target = ownedSource(action.getUserId(), payload.path("recordId").asLong());
            if (target == null) throw new BusinessException(404, "记录不存在或已删除");
            String expected = action.getTargetVersions().path(String.valueOf(target.getId())).asText(null);
            if (expected == null || !expected.equals(targetVersion(target))) throw new BusinessException(409, "记录已变化，请重新准备删除卡");
            records.softDelete(target.getId(), action.getUserId());
            return ActionExecution.applied(json.createObjectNode().put("recordId", target.getId()).put("deleted", true));
        }
        RecordDraftDTO draft = json.convertValue(payload.path("recordDraft"), RecordDraftDTO.class);
        if ("AI_ASSISTED".equals(draft.getMode()) && !draft.getGrowthSuggestions().isEmpty() && growthService != null
                && growthService.axesVersion(action.getUserId()) != draft.getAxesVersion())
            throw new BusinessException(409, "成长维度已变化，请重新准备审核卡");
        Long sourceId = "record.organize".equals(action.getKind()) ? draft.getSourceRecordId() : null;
        Long correctionOfId = payload.path("correctionOfRecordId").isIntegralNumber()
                ? payload.path("correctionOfRecordId").asLong() : null;
        Record correction = correctionOfId == null ? null : lockCorrectionSource(action.getUserId(), correctionOfId);
        if (correction != null) {
            String expectedCorrectionVersion = action.getTargetVersions()
                    .path(String.valueOf(correction.getId())).asText(null);
            if (expectedCorrectionVersion == null || !expectedCorrectionVersion.equals(targetVersion(correction)))
                throw new BusinessException(409, "更正来源已变化，请重新准备候选");
        }
        if ("ONLY_SAVE".equals(draft.getMode())) {
            if (sourceId != null) {
                ObjectNode unchanged = json.createObjectNode().put("recordId", sourceId).put("savedOnly", true);
                unchanged.put("outcome", "SAVED_ONLY_FALLBACK");
                return ActionExecution.applied(unchanged);
            }
            RecordDTO dto = new RecordDTO();
            dto.setContent(draft.getRawContent());
            dto.setCorrectionOfRecordId(correctionOfId);
            var created = records.create(dto, action.getUserId());
            ObjectNode saved = json.createObjectNode().put("recordId", created.getId()).put("savedOnly", true);
            saved.put("outcome", "SAVED_ONLY_FALLBACK");
            return ActionExecution.applied(saved);
        }

        Record record;
        if (sourceId == null) {
            RecordDTO dto = new RecordDTO();
            dto.setContent(draft.getRawContent());
            dto.setCorrectionOfRecordId(correctionOfId);
            var created = records.create(dto, action.getUserId());
            record = ownedSource(action.getUserId(), created.getId());
        } else {
            record = recordMapper.selectOwnedForUpdate(action.getUserId(), sourceId);
            if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
            if (!Objects.equals(action.getTargetVersions().path(String.valueOf(sourceId)).asText(), targetVersion(record)))
                throw new BusinessException(409, "记录原文已变化，请重新准备整理候选");
            validateOrganizeSource(action.getUserId(), record);
        }
        record.setAiProcessingAuthorized(true);
        record.setProcessingVersion(Math.max(1, record.getProcessingVersion() == null ? 0 : record.getProcessingVersion() + 1));
        record.setStatus(RecordStatus.REVIEWING);
        try { record.setGrowthSuggestions(json.writeValueAsString(draft.getGrowthSuggestions())); }
        catch (Exception e) { throw new BusinessException(400, "成长建议格式无效"); }
        record.setGrowthAxesVersion((long) draft.getAxesVersion());
        record.setFailReason(null);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);

        List<Long> approvedMemory = new ArrayList<>();
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
            chunkMapper.insert(chunk);
            if ("AI_ASSISTED".equals(draft.getMode()) || segment.isMemoryEnabled()) approvedMemory.add(chunk.getId());
        }
        List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO> growthAdjustments;
        try {
            growthAdjustments = draft.getGrowthSuggestions().isEmpty() ? List.of()
                    : json.convertValue(draft.getGrowthSuggestions(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO>>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "成长建议格式无效");
        }
        var confirmed = records.confirmReview(record.getId(), action.getUserId(), approvedMemory, List.of(),
                resolutions(payload), action.getActionId(), action.getExecutionVersion(),
                growthAdjustments, (long) draft.getAxesVersion());
        ObjectNode result = json.createObjectNode().put("recordId", record.getId()).put("reviewed", true)
                .put("memorySelections", approvedMemory.size()).put("memoryState", confirmed.getMemoryState().name());
        int confirmedResolutions = (int) resolutions(payload).stream().filter(r -> "confirmed".equalsIgnoreCase(r.getAction())).count();
        result.put("todoResolutionsConfirmed", confirmedResolutions).put("todoEffectsPreserved", true);
        if (correctionOfId != null) result.put("correctionOfRecordId", correctionOfId);
        if (confirmed.getQueuedEmbeddingJobIds() != null && !confirmed.getQueuedEmbeddingJobIds().isEmpty()) {
            result.set("jobIds", json.valueToTree(confirmed.getQueuedEmbeddingJobIds()));
            return ActionExecution.executing(result);
        }
        return ActionExecution.applied(result.put("outcome", "APPLIED"));
    }

    private RecordDraftDTO draftInput(JsonNode payload, Record source) {
        RecordDraftDTO input = new RecordDraftDTO();
        input.setRawContent(source == null ? payload.path("rawContent").asText(null) : source.getContent());
        input.setMode(payload.path("mode").asText(null));
        input.setPrivateInput(payload.path("privateInput").asBoolean(false));
        if (source != null) {
            input.setSourceRecordId(source.getId());
            input.setSourceRecordVersion(version(source));
        }
        return input;
    }

    private String defaultMode(UUID userId, JsonNode payload) {
        if (payload.path("privateInput").asBoolean(false)) return "ONLY_SAVE";
        if ("record.organize".equals(payload.path("kind").asText())) return "AI_ASSISTED";
        String mode = payload.path("mode").asText(null);
        return mode == null || mode.isBlank() ? settingsService.getSettings(userId).getDefaultRecordMode() : mode;
    }

    private void validatePayload(String kind, JsonNode payload) {
        if ("record.delete".equals(kind)) {
            if (payload == null || !payload.isObject() || !payload.path("recordId").canConvertToLong() || payload.path("recordId").asLong() < 1)
                throw invalid("缺少待删除记录 ID");
            return;
        }
        if (!KINDS.contains(kind) || payload == null || !payload.isObject()) throw invalid("记录操作不合法");
        payload.fieldNames().forEachRemaining(field -> { if (!ALLOWED_FIELDS.contains(field)) throw invalid("记录候选不允许字段: " + field); });
        if (payload.has("correctionOfRecordId") && (!payload.get("correctionOfRecordId").canConvertToLong()
                || payload.get("correctionOfRecordId").longValue() < 1)) throw invalid("correctionOfRecordId 必须为正整数");
        if ("record.create".equals(kind) && !payload.has("recordDraft") && !payload.hasNonNull("rawContent"))
            throw invalid("缺少原始记录内容");
        if ("record.organize".equals(kind) && !payload.hasNonNull("sourceRecordId")) throw invalid("缺少待整理记录 ID");
        if (payload.hasNonNull("rawContent") && payload.get("rawContent").asText().length() > 2000)
            throw invalid("记录内容不能超过 2000 字");
        if (payload.has("privateInput") && !payload.get("privateInput").isBoolean()) throw invalid("privateInput 必须为布尔值");
        if (payload.has("mode") && !payload.get("mode").isNull() && !payload.get("mode").isTextual()) throw invalid("mode 必须为文本");
        if (payload.has("sourceRecordId") && (!payload.get("sourceRecordId").canConvertToLong() || payload.get("sourceRecordId").longValue() < 1))
            throw invalid("sourceRecordId 必须为正整数");
        if (payload.has("recordDraft")) validateDraftShape(payload.get("recordDraft"));
    }

    private JsonNode deletePreview(UUID userId, JsonNode payload) {
        validatePayload("record.delete", payload);
        Record target = ownedSource(userId, payload.path("recordId").asLong());
        if (target == null) throw new BusinessException(404, "记录不存在或已删除");
        long points = 0; int count = 0;
        if (growthService != null) for (Object raw : growthService.entries(userId)) {
            var entry = json.convertValue(raw, org.xianshen.mumirrorb.pojo.VO.GrowthOverviewVO.Entry.class);
            if (Objects.equals(entry.getSourceRecordId(), target.getId())) { points += entry.getPoints() == null ? 0 : entry.getPoints(); count++; }
        }
        ObjectNode preview = json.createObjectNode().put("recordId", target.getId()).put("growthEntriesRemoved", count)
                .put("growthPointsRemoved", points).put("warning", "确认后会删除该记录关联的成长贡献");
        preview.putObject("targetVersions").put(String.valueOf(target.getId()), targetVersion(target));
        return preview;
    }

    private void validateDraftShape(JsonNode draft) {
        Set<String> allowed = Set.of("sourceRecordId", "sourceRecordVersion", "rawContent", "finalContent", "mode",
                "privateInput", "preparationStatus", "preparationError", "axesVersion", "growthSuggestions", "segments");
        if (draft == null || !draft.isObject()) throw invalid("recordDraft 必须为对象");
        draft.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid("recordDraft 不允许字段: " + field); });
        if (draft.has("privateInput") && !draft.get("privateInput").isBoolean()) throw invalid("privateInput 必须为布尔值");
        if (draft.has("axesVersion") && !draft.get("axesVersion").isIntegralNumber()) throw invalid("axesVersion 必须为整数");
        JsonNode segments = draft.get("segments");
        if (segments != null && segments.isArray()) {
            Set<String> segmentAllowed = Set.of("draftId", "text", "title", "summary", "contentType", "moods", "keywords", "tags", "taskStatus", "memoryEnabled");
            for (JsonNode segment : segments) {
                if (!segment.isObject()) throw invalid("片段必须为对象");
                segment.fieldNames().forEachRemaining(field -> { if (!segmentAllowed.contains(field)) throw invalid("片段不允许字段: " + field); });
            }
        }
    }

    private void validateDraft(RecordDraftDTO draft) {
        if (draft == null || draft.getRawContent() == null || draft.getRawContent().isBlank() || draft.getRawContent().length() > 2000)
            throw invalid("原始内容不能为空且不能超过 2000 字");
        if (draft.getFinalContent() == null || draft.getFinalContent().isBlank() || draft.getFinalContent().length() > 2000)
            throw invalid("最终正文不能为空且不能超过 2000 字");
        if (!Set.of("ONLY_SAVE", "AI_ASSISTED").contains(draft.getMode())) throw invalid("记录模式非法");
        if (!Set.of("READY", "SAVE_ONLY", "RUNNING").contains(draft.getPreparationStatus())) throw invalid("候选准备状态非法");
        if (draft.isPrivateInput() && !"ONLY_SAVE".equals(draft.getMode())) throw invalid("私有输入只能以仅保存模式提交");
        if (draft.getAxesVersion() < 0 || draft.getGrowthSuggestions() == null || draft.getGrowthSuggestions().size() > 5)
            throw invalid("成长建议格式无效");
        for (Object value : draft.getGrowthSuggestions()) {
            JsonNode suggestion = json.valueToTree(value);
            String dimension = suggestion.path("dimensionId").asText();
            int points = suggestion.path("points").asInt(-1);
            if (dimension.isBlank() || points < 0 || points > 100 || !suggestion.path("reason").isTextual())
                throw invalid("成长建议维度、分数或原因无效");
        }
        if (draft.getSegments() == null || draft.getSegments().isEmpty() || draft.getSegments().size() > 20)
            throw invalid("候选片段数量须为 1..20");
        int reviewedLength = 0;
        for (RecordDraftDTO.Segment segment : draft.getSegments()) {
            if (segment == null || segment.getText() == null || segment.getText().isBlank() || segment.getText().length() > 2000)
                throw invalid("片段内容不能为空且不能超过 2000 字");
            reviewedLength += segment.getText().length();
            if (segment.getTitle() != null && segment.getTitle().length() > 100) throw invalid("片段标题不能超过 100 字");
            if (segment.getSummary() != null && segment.getSummary().length() > 500) throw invalid("片段摘要不能超过 500 字");
            if (segment.getContentType() != null && !Set.of("todo", "thought", "learning", "plan", "note", "work", "social", "health").contains(segment.getContentType()))
                throw invalid("片段分类非法");
            validateStrings(segment.getMoods(), Set.of("happy", "excited", "satisfied", "grateful", "expecting", "calm", "bored", "confused", "anxious", "sad", "angry", "exhausted", "stressed"), "情绪");
            validateStrings(segment.getTags(), null, "标签");
            validateStrings(segment.getKeywords(), null, "关键词");
            if (segment.getTaskStatus() != null && !Set.of("not_started", "in_progress", "completed").contains(segment.getTaskStatus()))
                throw invalid("待办状态非法");
            if ("ONLY_SAVE".equals(draft.getMode()) && segment.isMemoryEnabled()) throw invalid("仅保存记录不能启用记忆");
        }
        if (reviewedLength > 2000) throw invalid("最终整理内容不能超过 2000 字");
    }

    private void validateStrings(List<String> values, Set<String> whitelist, String name) {
        if (values == null || values.size() > 30) throw invalid(name + "数量不能超过 30");
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 100 || !unique.add(value)) throw invalid(name + "格式非法或重复");
            if (whitelist != null && !whitelist.contains(value)) throw invalid(name + "值非法");
        }
    }

    private void normalizeSingleFinalContent(RecordDraftDTO draft) {
        if (draft.getSegments() != null && draft.getSegments().size() == 1) {
            // A single segment is the reviewed body. Keep its persisted text in sync with finalContent.
            draft.getSegments().get(0).setText(draft.getFinalContent());
        } else if (draft.getSegments() != null && !draft.getSegments().isEmpty()) {
            draft.setFinalContent(String.join("\n", draft.getSegments().stream().map(RecordDraftDTO.Segment::getText).toList()));
        }
    }

    private Record ownedSource(UUID userId, Long id) {
        if (id == null || id < 1) throw invalid("记录 ID 不合法");
        Record record = recordMapper.selectOne(new LambdaQueryWrapper<Record>().eq(Record::getId, id)
                .eq(Record::getUserId, userId).eq(Record::getSource, "user").isNull(Record::getDeletedAt));
        if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
        return record;
    }

    private Record correctionSource(UUID userId, JsonNode payload) {
        JsonNode id = payload.get("correctionOfRecordId");
        if (id == null || id.isNull()) return null;
        if (!id.canConvertToLong() || id.longValue() < 1) throw invalid("correctionOfRecordId 必须为正整数");
        Record source = ownedSource(userId, id.longValue());
        validateCorrectionSource(source);
        return source;
    }

    private Record lockCorrectionSource(UUID userId, Long id) {
        Record source = recordMapper.selectOwnedForUpdate(userId, id);
        if (source == null) throw new BusinessException(4041, "更正来源不存在或已删除");
        validateCorrectionSource(source);
        return source;
    }

    private void validateCorrectionSource(Record source) {
        if (source.getMemoryState() != org.xianshen.mumirrorb.common.enums.RecordMemoryState.READY
                && source.getMemoryState() != org.xianshen.mumirrorb.common.enums.RecordMemoryState.DISABLED)
            throw invalid("更正来源必须是本人已确认或已停止使用的只读记录");
    }

    private void addCorrectionTarget(ObjectNode preview, Record correction) {
        if (correction == null) return;
        preview.put("correctionOfRecordId", correction.getId());
        preview.with("targetVersions").put(String.valueOf(correction.getId()), targetVersion(correction));
    }

    private void validateOrganizeSource(UUID userId, Record source) {
        if (source == null) return;
        boolean authorizedFailure = source.getStatus() == RecordStatus.FAILED
                && Boolean.TRUE.equals(source.getAiProcessingAuthorized());
        boolean untouchedOnlySave = source.getStatus() == RecordStatus.RECORD_ONLY
                && !Boolean.TRUE.equals(source.getAiProcessingAuthorized());
        if (!authorizedFailure && !untouchedOnlySave) throw invalid("只有仅保存记录或授权整理失败的记录可以重新准备");
        if (authorizedFailure && chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getRecordId, source.getId())) > 0)
            throw new BusinessException(409, "失败记录含部分片段，需等待 Task 5 记忆生命周期处理后再重试；当前未清理或覆盖片段");
    }

    private ObjectNode preview(Record source, RecordDraftDTO draft, String preparationStatus) {
        ObjectNode preview = json.createObjectNode();
        ObjectNode versions = preview.putObject("targetVersions");
        if (source != null) versions.put(String.valueOf(source.getId()), targetVersion(source));
        preview.set("recordDraft", json.valueToTree(draft));
        preview.put("before", source == null ? "NONE" : source.getStatus() == RecordStatus.FAILED ? "FAILED" : "RECORD_ONLY");
        preview.put("after", "PENDING_USER_CONFIRMATION");
        preview.putArray("effects").add(source == null ? "record.create" : "record.organize");
        preview.put("businessWrites", false);
        preview.put("authorizationGranted", false);
        preview.put("preparationStatus", preparationStatus);
        return preview;
    }

    private static Long sourceId(JsonNode payload) {
        JsonNode id = payload.get("sourceRecordId");
        return id != null && id.canConvertToLong() ? id.longValue() : null;
    }
    private static long version(Record record) { return record.getProcessingVersion() == null ? 0 : record.getProcessingVersion(); }
    private static String targetVersion(Record record) {
        try {
            String source = version(record) + "|" + record.getUpdatedAt() + "|" + record.getContent();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private List<TodoResolutionDTO> resolutions(JsonNode payload) {
        JsonNode node = payload.get("todoResolutions");
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw invalid("todoResolutions 必须为数组");
        return json.convertValue(node, json.getTypeFactory().constructCollectionType(List.class, TodoResolutionDTO.class));
    }
    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
