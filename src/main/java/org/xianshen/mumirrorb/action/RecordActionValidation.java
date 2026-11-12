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

/** RecordActionValidation keeps the service entry points and transaction boundaries in RecordActionHandler. */
final class RecordActionValidation {
    private final RecordActionHandler owner;
    RecordActionValidation(RecordActionHandler owner) { this.owner=owner; }


    protected RecordDraftDTO draftInput(JsonNode payload, Record source) {
        RecordDraftDTO input = new RecordDraftDTO();
        input.setRawContent(source == null ? payload.path("rawContent").asText(null) : source.getContent());
        input.setMode(payload.path("mode").asText(null));
        input.setPrivateInput(payload.path("privateInput").asBoolean(false));
        if (source != null) {
            input.setSourceRecordId(source.getId());
            input.setSourceRecordVersion(owner.version(source));
        }
        return input;
    }



    protected String defaultMode(UUID userId, JsonNode payload) {
        if (payload.path("privateInput").asBoolean(false)) return "ONLY_SAVE";
        if ("record.organize".equals(payload.path("kind").asText())) return "AI_ASSISTED";
        String mode = payload.path("mode").asText(null);
        return mode == null || mode.isBlank() ? owner.settingsService.getSettings(userId).getDefaultRecordMode() : mode;
    }



    protected void validatePayload(String kind, JsonNode payload) {
        if ("record.delete".equals(kind)) {
            if (payload == null || !payload.isObject() || !payload.path("recordId").canConvertToLong() || payload.path("recordId").asLong() < 1)
                throw owner.invalid("缺少待删除记录 ID");
            return;
        }
        if (!owner.KINDS.contains(kind) || payload == null || !payload.isObject()) throw owner.invalid("记录操作不合法");
        payload.fieldNames().forEachRemaining(field -> { if (!owner.ALLOWED_FIELDS.contains(field)) throw owner.invalid("记录候选不允许字段: " + field); });
        if (payload.has("correctionOfRecordId") && (!payload.get("correctionOfRecordId").canConvertToLong()
                || payload.get("correctionOfRecordId").longValue() < 1)) throw owner.invalid("correctionOfRecordId 必须为正整数");
        if ("record.create".equals(kind) && !payload.has("recordDraft") && !payload.hasNonNull("rawContent"))
            throw owner.invalid("缺少原始记录内容");
        if ("record.organize".equals(kind) && !payload.hasNonNull("sourceRecordId")) throw owner.invalid("缺少待整理记录 ID");
        if (payload.hasNonNull("rawContent") && payload.get("rawContent").asText().length() > 2000)
            throw owner.invalid("记录内容不能超过 2000 字");
        if (payload.has("privateInput") && !payload.get("privateInput").isBoolean()) throw owner.invalid("privateInput 必须为布尔值");
        if (payload.has("mode") && !payload.get("mode").isNull() && !payload.get("mode").isTextual()) throw owner.invalid("mode 必须为文本");
        if (payload.has("sourceRecordId") && (!payload.get("sourceRecordId").canConvertToLong() || payload.get("sourceRecordId").longValue() < 1))
            throw owner.invalid("sourceRecordId 必须为正整数");
        if (payload.has("recordDraft")) owner.validateDraftShape(payload.get("recordDraft"));
    }



    protected JsonNode deletePreview(UUID userId, JsonNode payload) {
        owner.validatePayload("record.delete", payload);
        Record target = owner.ownedSource(userId, payload.path("recordId").asLong());
        if (target == null) throw new BusinessException(404, "记录不存在或已删除");
        long points = 0; int count = 0;
        if (owner.growthService != null) for (Object raw : owner.growthService.entries(userId)) {
            var entry = owner.json.convertValue(raw, org.xianshen.mumirrorb.pojo.VO.GrowthOverviewVO.Entry.class);
            if (Objects.equals(entry.getSourceRecordId(), target.getId())) { points += entry.getPoints() == null ? 0 : entry.getPoints(); count++; }
        }
        ObjectNode preview = owner.json.createObjectNode().put("recordId", target.getId()).put("growthEntriesRemoved", count)
                .put("growthPointsRemoved", points).put("warning", "确认后会删除该记录关联的成长贡献");
        preview.putObject("targetVersions").put(String.valueOf(target.getId()), owner.targetVersion(target));
        return preview;
    }



    protected void validateDraftShape(JsonNode draft) {
        Set<String> allowed = Set.of("sourceRecordId", "sourceRecordVersion", "rawContent", "finalContent", "mode",
                "privateInput", "preparationStatus", "preparationError", "axesVersion", "growthSuggestions", "todoSuggestions", "segments");
        if (draft == null || !draft.isObject()) throw owner.invalid("recordDraft 必须为对象");
        draft.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw owner.invalid("recordDraft 不允许字段: " + field); });
        if (draft.has("privateInput") && !draft.get("privateInput").isBoolean()) throw owner.invalid("privateInput 必须为布尔值");
        if (draft.has("axesVersion") && !draft.get("axesVersion").isIntegralNumber()) throw owner.invalid("axesVersion 必须为整数");
        JsonNode segments = draft.get("segments");
        if (segments != null && segments.isArray()) {
            Set<String> segmentAllowed = Set.of("draftId", "text", "title", "summary", "contentType", "moods", "keywords", "tags", "taskStatus", "memoryEnabled", "todoEnabled");
            for (JsonNode segment : segments) {
                if (!segment.isObject()) throw owner.invalid("片段必须为对象");
                if (segment.has("todoEnabled") && !segment.get("todoEnabled").isBoolean()) throw owner.invalid("todoEnabled 必须为布尔值");
                segment.fieldNames().forEachRemaining(field -> { if (!segmentAllowed.contains(field)) throw owner.invalid("片段不允许字段: " + field); });
            }
        }
    }



    protected void validateDraft(RecordDraftDTO draft) {
        if (draft == null || draft.getRawContent() == null || draft.getRawContent().isBlank() || draft.getRawContent().length() > 2000)
            throw owner.invalid("原始内容不能为空且不能超过 2000 字");
        if (draft.getFinalContent() == null || draft.getFinalContent().isBlank() || draft.getFinalContent().length() > 2000)
            throw owner.invalid("最终正文不能为空且不能超过 2000 字");
        if (!Set.of("ONLY_SAVE", "AI_ASSISTED").contains(draft.getMode())) throw owner.invalid("记录模式非法");
        if (!Set.of("READY", "SAVE_ONLY", "RUNNING").contains(draft.getPreparationStatus())) throw owner.invalid("候选准备状态非法");
        if (draft.isPrivateInput() && !"ONLY_SAVE".equals(draft.getMode())) throw owner.invalid("私有输入只能以仅保存模式提交");
        if (draft.getAxesVersion() < 0 || draft.getGrowthSuggestions() == null || draft.getGrowthSuggestions().size() > 5)
            throw owner.invalid("成长建议格式无效");
        for (Object value : draft.getGrowthSuggestions()) {
            JsonNode suggestion = owner.json.valueToTree(value);
            String dimension = suggestion.path("dimensionId").asText();
            int points = suggestion.path("points").asInt(-1);
            if (dimension.isBlank() || points < 0 || points > 100 || !suggestion.path("reason").isTextual())
                throw owner.invalid("成长建议维度、分数或原因无效");
        }
        if (draft.getSegments() == null || draft.getSegments().isEmpty() || draft.getSegments().size() > 20)
            throw owner.invalid("候选片段数量须为 1..20");
        int reviewedLength = 0;
        for (RecordDraftDTO.Segment segment : draft.getSegments()) {
            if (segment != null && segment.isTodoEnabled() && (!"AI_ASSISTED".equals(draft.getMode())
                    || !Set.of("todo", "plan").contains(Objects.toString(segment.getContentType(), ""))))
                throw owner.invalid("只有待办分类可授权加入侧栏待办");
            if (segment == null || segment.getText() == null || segment.getText().isBlank() || segment.getText().length() > 2000)
                throw owner.invalid("片段内容不能为空且不能超过 2000 字");
            reviewedLength += segment.getText().length();
            if (segment.getTitle() != null && segment.getTitle().length() > 100) throw owner.invalid("片段标题不能超过 100 字");
            if (segment.getSummary() != null && segment.getSummary().length() > 500) throw owner.invalid("片段摘要不能超过 500 字");
            if (segment.getContentType() != null && !Set.of("todo", "thought", "learning", "plan", "note", "work", "social", "health").contains(segment.getContentType()))
                throw owner.invalid("片段分类非法");
            owner.validateStrings(segment.getMoods(), Set.of("happy", "excited", "satisfied", "grateful", "expecting", "calm", "bored", "confused", "anxious", "sad", "angry", "exhausted", "stressed"), "情绪");
            owner.validateStrings(segment.getTags(), null, "标签");
            owner.validateStrings(segment.getKeywords(), null, "关键词");
            if (segment.getTaskStatus() != null && !Set.of("not_started", "in_progress", "completed").contains(segment.getTaskStatus()))
                throw owner.invalid("待办状态非法");
            if ("ONLY_SAVE".equals(draft.getMode()) && segment.isMemoryEnabled()) throw owner.invalid("仅保存记录不能启用记忆");
        }
        if (reviewedLength > 2000) throw owner.invalid("最终整理内容不能超过 2000 字");
    }



    protected void validateStrings(List<String> values, Set<String> whitelist, String name) {
        if (values == null || values.size() > 30) throw owner.invalid(name + "数量不能超过 30");
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 100 || !unique.add(value)) throw owner.invalid(name + "格式非法或重复");
            if (whitelist != null && !whitelist.contains(value)) throw owner.invalid(name + "值非法");
        }
    }



    protected void normalizeSingleFinalContent(RecordDraftDTO draft) {
        if (draft.getSegments() != null) {
            for (RecordDraftDTO.Segment segment : draft.getSegments()) {
                if ("plan".equals(segment.getContentType())) segment.setContentType("todo");
            }
        }
        if (draft.getSegments() != null && draft.getSegments().size() == 1) {
            // A single segment is the reviewed body. Keep its persisted text in sync with finalContent.
            draft.getSegments().get(0).setText(draft.getFinalContent());
        } else if (draft.getSegments() != null && !draft.getSegments().isEmpty()) {
            draft.setFinalContent(String.join("\n", draft.getSegments().stream().map(RecordDraftDTO.Segment::getText).toList()));
        }
    }



    protected Record ownedSource(UUID userId, Long id) {
        if (id == null || id < 1) throw owner.invalid("记录 ID 不合法");
        Record record = owner.recordMapper.selectOne(new LambdaQueryWrapper<Record>().eq(Record::getId, id)
                .eq(Record::getUserId, userId).eq(Record::getSource, "user").isNull(Record::getDeletedAt));
        if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
        return record;
    }



    protected Record correctionSource(UUID userId, JsonNode payload) {
        JsonNode id = payload.get("correctionOfRecordId");
        if (id == null || id.isNull()) return null;
        if (!id.canConvertToLong() || id.longValue() < 1) throw owner.invalid("correctionOfRecordId 必须为正整数");
        Record source = owner.ownedSource(userId, id.longValue());
        owner.validateCorrectionSource(source);
        return source;
    }



    protected Record lockCorrectionSource(UUID userId, Long id) {
        Record source = owner.recordMapper.selectOwnedForUpdate(userId, id);
        if (source == null) throw new BusinessException(4041, "更正来源不存在或已删除");
        owner.validateCorrectionSource(source);
        return source;
    }



    protected void validateCorrectionSource(Record source) {
        if (source.getMemoryState() != org.xianshen.mumirrorb.common.enums.RecordMemoryState.READY
                && source.getMemoryState() != org.xianshen.mumirrorb.common.enums.RecordMemoryState.DISABLED)
            throw owner.invalid("更正来源必须是本人已确认或已停止使用的只读记录");
    }



    protected void addCorrectionTarget(ObjectNode preview, Record correction) {
        if (correction == null) return;
        preview.put("correctionOfRecordId", correction.getId());
        preview.with("targetVersions").put(String.valueOf(correction.getId()), owner.targetVersion(correction));
    }



    protected void validateOrganizeSource(UUID userId, Record source) {
        if (source == null) return;
        boolean authorizedFailure = source.getStatus() == RecordStatus.FAILED
                && Boolean.TRUE.equals(source.getAiProcessingAuthorized());
        boolean untouchedOnlySave = source.getStatus() == RecordStatus.RECORD_ONLY
                && !Boolean.TRUE.equals(source.getAiProcessingAuthorized());
        if (!authorizedFailure && !untouchedOnlySave) throw owner.invalid("只有仅保存记录或授权整理失败的记录可以重新准备");
        if (authorizedFailure && owner.chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getRecordId, source.getId())) > 0)
            throw new BusinessException(409, "失败记录含部分片段，需等待 Task 5 记忆生命周期处理后再重试；当前未清理或覆盖片段");
    }



    protected ObjectNode preview(Record source, RecordDraftDTO draft, String preparationStatus) {
        ObjectNode preview = owner.json.createObjectNode();
        ObjectNode versions = preview.putObject("targetVersions");
        if (source != null) versions.put(String.valueOf(source.getId()), owner.targetVersion(source));
        preview.set("recordDraft", owner.json.valueToTree(draft));
        preview.put("before", source == null ? "NONE" : source.getStatus() == RecordStatus.FAILED ? "FAILED" : "RECORD_ONLY");
        preview.put("after", "PENDING_USER_CONFIRMATION");
        preview.putArray("effects").add(source == null ? "record.create" : "record.organize");
        preview.put("businessWrites", false);
        preview.put("authorizationGranted", false);
        preview.put("preparationStatus", preparationStatus);
        return preview;
    }


    protected List<TodoResolutionDTO> resolutions(JsonNode payload) {
        JsonNode node = payload.get("todoResolutions");
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw owner.invalid("todoResolutions 必须为数组");
        return owner.json.convertValue(node, owner.json.getTypeFactory().constructCollectionType(List.class, TodoResolutionDTO.class));
    }
}
