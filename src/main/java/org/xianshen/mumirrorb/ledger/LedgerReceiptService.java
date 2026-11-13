package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.service.VaultService;

import java.util.Set;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.LedgerModels.Recognition;

@Service
public class LedgerReceiptService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> STATES = Set.of("BILL", "NOT_BILL", "NEEDS_REVIEW");
    private final VaultService vault;
    private final AiGrpcClient ai;
    private final ObjectMapper json;

    public LedgerReceiptService(VaultService vault, AiGrpcClient ai, ObjectMapper json) {
        this.vault = vault;
        this.ai = ai;
        this.json = json;
    }

    public Recognition recognize(UUID owner, Long itemId) {
        if (itemId == null || itemId < 1) throw new BusinessException(400, "请选择支付截图");
        var file = vault.preview(owner, itemId);
        var mime = file.item().getMime();
        if (!IMAGE_TYPES.contains(mime)) throw new BusinessException(400, "请选择 PNG、JPG 或 WebP 图片");
        if (file.data().length > 3 * 1024 * 1024) throw new BusinessException(400, "请将截图裁剪至 3 MB 以内");
        try {
            var response = ai.recognizeLedgerReceipt(owner, file.data(), mime, LedgerService.today());
            var state = STATES.contains(response.getStatus()) ? response.getStatus() : "NEEDS_REVIEW";
            var draft = response.getDraftJson().isBlank() ? null : json.readTree(response.getDraftJson());
            if (draft != null && draft.path("flows").isArray()) {
                for (var item : draft.path("flows")) {
                    if (item instanceof ObjectNode flow) flow.put("evidenceId", itemId);
                }
            }
            return new Recognition(state, draft, response.getMessage());
        } catch (StatusRuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Recognition("NEEDS_REVIEW", null, "暂时无法识别这张截图，可以手动填写");
        }
    }
}
