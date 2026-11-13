package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.action.ActionExecution;
import org.xianshen.mumirrorb.action.ActionHandler;
import org.xianshen.mumirrorb.action.AuthorizedAction;
import org.xianshen.mumirrorb.common.exception.BusinessException;

import java.util.Set;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.LedgerModels.AppendInput;
import static org.xianshen.mumirrorb.ledger.LedgerModels.EntryInput;

@Component
public class LedgerActionHandler implements ActionHandler {
    public static final String KIND = "ledger.create";
    private final LedgerService ledger;
    private final ObjectMapper json;

    public LedgerActionHandler(LedgerService ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND);
    }

    @Override
    public JsonNode validateAndPreview(UUID owner, String kind, JsonNode payload) {
        var entry = entryInput(payload, null);
        ledger.validateDraft(owner, entry, target(payload));
        var preview = (ObjectNode) payload.deepCopy();
        preview.remove("requestId");
        preview.set("targetVersions", json.createObjectNode());
        return preview;
    }

    @Override
    public JsonNode persistedPayload(String kind, JsonNode payload, JsonNode preview) {
        var persisted = (ObjectNode) payload.deepCopy();
        var date = LedgerService.today().toString();
        persisted.put("preparedOn", date);
        for (var node : persisted.path("flows")) {
            if (node instanceof ObjectNode flow && !flow.hasNonNull("occurredOn")) {
                flow.put("occurredOn", date);
            }
        }
        return persisted;
    }

    @Override
    public void validateEdit(String kind, JsonNode original, JsonNode edited) {
        requireCurrent(original);
    }

    @Override
    public ActionExecution execute(AuthorizedAction action) {
        var payload = action.getPayload();
        requireCurrent(payload);
        var input = entryInput(payload, action.getActionId());
        var appendTo = target(payload);
        var result = appendTo == null ? ledger.create(action.getUserId(), input)
                : ledger.append(action.getUserId(), appendTo, new AppendInput(action.getActionId(), input.flows()));
        return ActionExecution.applied(json.createObjectNode().put("ledgerRecordId", result.id().toString())
                .put("title", result.title()).put("link", "/ledger?record=" + result.id()));
    }

    private EntryInput entryInput(JsonNode payload, UUID key) {
        if (!(payload instanceof ObjectNode object)) throw new BusinessException(400, "账目候选格式无效");
        var fields = object.deepCopy();
        fields.remove(java.util.List.of("recordId", "preparedOn", "requestId", "targetVersions"));
        try {
            var entry = json.convertValue(fields, EntryInput.class);
            return new EntryInput(key, entry.title(), entry.description(), entry.flows(), entry.diaryMode(), entry.diaryText());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "请核对候选里的金额、日期和分类");
        }
    }

    private static UUID target(JsonNode payload) {
        if (!payload.hasNonNull("recordId")) return null;
        try {
            return UUID.fromString(payload.path("recordId").asText());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "关联账目标识无效");
        }
    }

    private static void requireCurrent(JsonNode payload) {
        if (!LedgerService.today().toString().equals(payload.path("preparedOn").asText())) {
            throw new BusinessException(409, "这张记账卡已跨日，请为今天实际发生的流水重新创建");
        }
    }
}
