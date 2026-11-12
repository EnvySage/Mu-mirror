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

/** RecordActionPreparation keeps the service entry points and transaction boundaries in RecordActionHandler. */
final class RecordActionPreparation {
    private final RecordActionHandler owner;
    RecordActionPreparation(RecordActionHandler owner) { this.owner=owner; }


     public Set<String> kinds() { return owner.KINDS; }




    public boolean asynchronousPreparation(UUID userId, String kind, JsonNode payload) {
        if (!owner.KINDS.contains(kind) || "record.delete".equals(kind)) return false;
        if (payload.path("privateInput").asBoolean(false) || "ONLY_SAVE".equals(payload.path("mode").asText())) return false;
        if ("AI_ASSISTED".equals(payload.path("mode").asText())) return true;
        return "AI_ASSISTED".equals(owner.settingsService.getSettings(userId).getDefaultRecordMode());
    }




    public JsonNode initialPreparationPreview(UUID userId, String kind, JsonNode payload) {
        owner.validatePayload(kind, payload);
        Record source = "record.organize".equals(kind) ? owner.ownedSource(userId, owner.sourceId(payload)) : null;
        owner.validateOrganizeSource(userId, source);
        Record correction = owner.correctionSource(userId, payload);
        RecordDraftDTO draft = owner.draftInput(payload, source);
        if (draft.getMode() == null || draft.getMode().isBlank())
            draft.setMode(source == null ? owner.defaultMode(userId, payload) : "AI_ASSISTED");
        draft.setFinalContent(draft.getRawContent());
        draft.setPreparationStatus("RUNNING");
        draft.setAxesVersion(0);
        draft.setGrowthSuggestions(List.of());
        var segment = new RecordDraftDTO.Segment();
        segment.setDraftId(UUID.randomUUID());
        segment.setText(draft.getRawContent());
        draft.setSegments(List.of(segment));
        ObjectNode preview = owner.preview(source, draft, "RUNNING");
        owner.addCorrectionTarget(preview, correction);
        return preview;
    }




    public JsonNode completePreparationPreview(UUID userId, String kind, JsonNode payload) {
        ObjectNode sourcePayload = (ObjectNode) payload.deepCopy();
        sourcePayload.remove("recordDraft");
        return owner.validateAndPreview(userId, kind, sourcePayload);
    }




    public JsonNode failedPreparationPreview(UUID userId, String kind, JsonNode payload, Exception error) {
        ObjectNode sourcePayload = (ObjectNode) payload.deepCopy();
        sourcePayload.remove("recordDraft");
        sourcePayload.put("mode", "ONLY_SAVE");
        Record source = "record.organize".equals(kind) ? owner.ownedSource(userId, owner.sourceId(sourcePayload)) : null;
        owner.validateOrganizeSource(userId, source);
        Record correction = owner.correctionSource(userId, sourcePayload);
        RecordDraftDTO fallback = owner.draftInput(sourcePayload, source);
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
        ObjectNode preview = owner.preview(source, fallback, "READY");
        owner.addCorrectionTarget(preview, correction);
        return preview;
    }




    public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        if ("record.delete".equals(kind)) return owner.deletePreview(userId, payload);
        owner.validatePayload(kind, payload);
        Record source = "record.organize".equals(kind) ? owner.ownedSource(userId, owner.sourceId(payload)) : null;
        owner.validateOrganizeSource(userId, source);
        Record correction = owner.correctionSource(userId, payload);
        if (payload.has("recordDraft")) owner.validateDraftShape(payload.get("recordDraft"));
        RecordDraftDTO draft = payload.has("recordDraft")
                ? owner.json.convertValue(payload.get("recordDraft"), RecordDraftDTO.class)
                : owner.drafts.prepare(userId, owner.draftInput(payload, source));
        if (source != null && !Objects.equals(source.getContent(), draft.getRawContent()))
            throw owner.invalid("整理候选原文与来源记录不一致");
        draft.setSourceRecordId(source == null ? null : source.getId());
        draft.setSourceRecordVersion(source == null ? null : owner.version(source));
        owner.normalizeSingleFinalContent(draft);
        ObjectNode preview = owner.preview(source, draft, draft.getPreparationStatus());
        owner.addCorrectionTarget(preview, correction);
        return preview;
    }




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




    public void validateEdit(String kind, JsonNode originalPayload, JsonNode editedPayload) {
        JsonNode originalDraft = originalPayload.path("recordDraft");
        boolean wasPrivate = originalPayload.path("privateInput").asBoolean(false)
                || originalDraft.path("privateInput").asBoolean(false);
        JsonNode editedDraft = editedPayload.path("recordDraft");
        boolean remainsPrivate = editedPayload.path("privateInput").asBoolean(false) || editedDraft.path("privateInput").asBoolean(false);
        if (wasPrivate && (!remainsPrivate || !"ONLY_SAVE".equals(editedDraft.path("mode").asText())))
            throw owner.invalid("私有输入不能改为 AI 整理或记忆授权");
    }




    public void validatePreparation(String kind, JsonNode payload, JsonNode preview) {
        if ("record.delete".equals(kind)) return;
        RecordDraftDTO draft = owner.json.convertValue(payload.path("recordDraft"), RecordDraftDTO.class);
        owner.validateDraft(draft);
        for (RecordDraftDTO.Segment segment : draft.getSegments()) {
            if (segment.getText() == null || segment.getText().isBlank() || segment.getText().length() > 2000)
                throw owner.invalid("片段内容不能为空且不能超过 2000 字");
        }
    }
}
