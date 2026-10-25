package org.xianshen.mumirrorb.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.RecordMemoryLifecycleService;

import java.util.Set;
import java.util.UUID;

/** Stops future memory use only after a durable user confirmation card is confirmed. */
@Component
@RequiredArgsConstructor
public class RecordMemoryActionHandler implements ActionHandler {
    private static final String KIND = "record.memory.disable";
    private final RecordMapper records;
    private final RecordMemoryLifecycleService lifecycle;
    private final ObjectMapper json;

    @Override public Set<String> kinds() { return Set.of(KIND); }

    @Override
    public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        if (!KIND.equals(kind) || payload == null || !payload.isObject()) throw invalid("记忆操作不合法");
        payload.fieldNames().forEachRemaining(key -> {
            if (!Set.of("recordId", "expectedRecordVersion").contains(key)) throw invalid("记忆操作不允许字段: " + key);
        });
        long id = payload.path("recordId").asLong(-1);
        if (id < 1) throw invalid("recordId 必须为正整数");
        Record record = records.selectOne(new LambdaQueryWrapper<Record>().eq(Record::getId, id)
                .eq(Record::getUserId, userId).eq(Record::getSource, "user").isNull(Record::getDeletedAt));
        if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
        if (record.getMemoryState() != RecordMemoryState.READY)
            throw invalid("只有已就绪记忆可以永久移除");
        ObjectNode preview = json.createObjectNode();
        ObjectNode targetVersions = preview.putObject("targetVersions");
        targetVersions.put(String.valueOf(id), String.valueOf(record.getProcessingVersion() == null ? 0 : record.getProcessingVersion()));
        preview.put("recordId", id).put("currentMemoryState", "READY").put("afterMemoryState", "DISABLED")
                .put("sourceReadonly", true).put("keepsGrowthLedger", true)
                .put("derivedContextInvalidated", true).put("businessWrites", false)
                .put("permanent", true)
                .put("description", "永久移除后，这条记录不再用于检索、对话和画像，且不能重新启用。原文保持只读，已确认的成长账本保留。");
        preview.putArray("effects").add("remove_memory_permanently").add("invalidate_derived_context");
        return preview;
    }

    @Override
    @Transactional
    public ActionExecution execute(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        long id = payload.path("recordId").asLong();
        Record record = records.selectOwnedForUpdate(action.getUserId(), id);
        if (record == null) throw new BusinessException(4041, "记录不存在或已删除");
        String expected = action.getTargetVersions().path(String.valueOf(id)).asText();
        int version = record.getProcessingVersion() == null ? 0 : record.getProcessingVersion();
        if (!expected.equals(String.valueOf(version))) throw new BusinessException(409, "记录已变化，请重新准备永久移除记忆操作");
        lifecycle.disable(action.getUserId(), id, version);
        ObjectNode result = json.createObjectNode().put("recordId", id).put("outcome", "APPLIED")
                .put("memoryState", "DISABLED").put("sourceReadonly", true)
                .put("derivedContextInvalidated", true).put("growthLedgerPreserved", true);
        return ActionExecution.applied(result);
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
