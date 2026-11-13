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
    protected static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RecordActionHandler.class);
    private final RecordActionPreparation recordActionPreparation = new RecordActionPreparation(this);
    private final RecordActionExecution recordActionExecution = new RecordActionExecution(this);
    private final RecordActionValidation recordActionValidation = new RecordActionValidation(this);


    protected static final Set<String> KINDS = Set.of("record.create", "record.organize", "record.delete");
    protected static final Set<String> ALLOWED_FIELDS = Set.of("rawContent", "mode", "privateInput", "sourceRecordId",
            "sourceRecordVersion", "recordDraft", "todoResolutions", "correctionOfRecordId");
    protected final RecordDraftService drafts;
    protected final RecordService records;
    protected final RecordMapper recordMapper;
    protected final ChunkMapper chunkMapper;
    protected final ObjectMapper json;
    protected final org.xianshen.mumirrorb.service.SettingsService settingsService;
    @org.springframework.beans.factory.annotation.Autowired
    protected org.xianshen.mumirrorb.config.RecordMemoryProperties recordMemoryProperties =
            new org.xianshen.mumirrorb.config.RecordMemoryProperties();
    @org.springframework.beans.factory.annotation.Autowired protected org.xianshen.mumirrorb.service.GrowthService growthService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    protected org.xianshen.mumirrorb.achievement.AchievementActivity achievementActivity;

    @Override public Set<String> kinds() {
        return recordActionPreparation.kinds();
    }

    @Override
    public boolean asynchronousPreparation(UUID userId, String kind, JsonNode payload) {
        return recordActionPreparation.asynchronousPreparation(userId, kind, payload);
    }

    @Override
    public JsonNode initialPreparationPreview(UUID userId, String kind, JsonNode payload) {
        return recordActionPreparation.initialPreparationPreview(userId, kind, payload);
    }

    @Override
    public JsonNode completePreparationPreview(UUID userId, String kind, JsonNode payload) {
        return recordActionPreparation.completePreparationPreview(userId, kind, payload);
    }

    @Override
    public JsonNode failedPreparationPreview(UUID userId, String kind, JsonNode payload, Exception error) {
        return recordActionPreparation.failedPreparationPreview(userId, kind, payload, error);
    }

    @Override
    public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        return recordActionPreparation.validateAndPreview(userId, kind, payload);
    }

    @Override
    public JsonNode persistedPayload(String kind, JsonNode payload, JsonNode preview) {
        return recordActionPreparation.persistedPayload(kind, payload, preview);
    }

    @Override
    public void validateEdit(String kind, JsonNode originalPayload, JsonNode editedPayload) {
        recordActionPreparation.validateEdit(kind, originalPayload, editedPayload);
    }

    @Override
    public void validatePreparation(String kind, JsonNode payload, JsonNode preview) {
        recordActionPreparation.validatePreparation(kind, payload, preview);
    }

    @Override
    @Transactional
    public ActionExecution execute(AuthorizedAction action) {
        return recordActionExecution.execute(action);
    }

    protected RecordDraftDTO draftInput(JsonNode payload, Record source) {
        return recordActionValidation.draftInput(payload, source);
    }

    protected String defaultMode(UUID userId, JsonNode payload) {
        return recordActionValidation.defaultMode(userId, payload);
    }

    protected void validatePayload(String kind, JsonNode payload) {
        recordActionValidation.validatePayload(kind, payload);
    }

    protected JsonNode deletePreview(UUID userId, JsonNode payload) {
        return recordActionValidation.deletePreview(userId, payload);
    }

    protected void validateDraftShape(JsonNode draft) {
        recordActionValidation.validateDraftShape(draft);
    }

    protected void validateDraft(RecordDraftDTO draft) {
        recordActionValidation.validateDraft(draft);
    }

    protected void validateStrings(List<String> values, Set<String> whitelist, String name) {
        recordActionValidation.validateStrings(values, whitelist, name);
    }

    protected void normalizeSingleFinalContent(RecordDraftDTO draft) {
        recordActionValidation.normalizeSingleFinalContent(draft);
    }

    protected Record ownedSource(UUID userId, Long id) {
        return recordActionValidation.ownedSource(userId, id);
    }

    protected Record correctionSource(UUID userId, JsonNode payload) {
        return recordActionValidation.correctionSource(userId, payload);
    }

    protected Record lockCorrectionSource(UUID userId, Long id) {
        return recordActionValidation.lockCorrectionSource(userId, id);
    }

    protected void validateCorrectionSource(Record source) {
        recordActionValidation.validateCorrectionSource(source);
    }

    protected void addCorrectionTarget(ObjectNode preview, Record correction) {
        recordActionValidation.addCorrectionTarget(preview, correction);
    }

    protected void validateOrganizeSource(UUID userId, Record source) {
        recordActionValidation.validateOrganizeSource(userId, source);
    }

    protected ObjectNode preview(Record source, RecordDraftDTO draft, String preparationStatus) {
        return recordActionValidation.preview(source, draft, preparationStatus);
    }

    protected static Long sourceId(JsonNode payload) {
        JsonNode id = payload.get("sourceRecordId");
        return id != null && id.canConvertToLong() ? id.longValue() : null;
    }
    protected static long version(Record record) { return record.getProcessingVersion() == null ? 0 : record.getProcessingVersion(); }
    protected static String targetVersion(Record record) {
        try {
            String source = version(record) + "|" + record.getUpdatedAt() + "|" + record.getContent();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    protected List<TodoResolutionDTO> resolutions(JsonNode payload) {
        return recordActionValidation.resolutions(payload);
    }
    protected static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
