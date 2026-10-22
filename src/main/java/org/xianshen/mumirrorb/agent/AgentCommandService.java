package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.AgentCommandReceiptMapper;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.mapper.ToolCallMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentCommandReceipt;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DO.ToolCall;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Owns the atomic create-record command, success receipt and one Java audit row. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCommandService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AgentRunMapper runMapper;
    private final AgentCommandReceiptMapper receiptMapper;
    private final RecordService recordService;
    private final ToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public AgentCommandResponse createRecord(VerifiedGrant grant, UUID requestRunId,
                                             UUID callId, String content) {
        long started = System.nanoTime();
        if (grant == null || requestRunId == null || callId == null) {
            throw AgentCommandException.forbidden();
        }
        if (content == null || content.isBlank()) throw AgentCommandException.invalid("EMPTY_CONTENT");
        if (content.length() > 2000) throw AgentCommandException.invalid("CONTENT_TOO_LONG");
        validateGrantShape(grant, requestRunId, callId);

        try {
            AgentRun run = runMapper.lockByRunId(grant.runId());
            validateLiveRun(grant, run);
            String requestHash = AgentWriteGrantService.sha256(content);

            AgentCommandReceipt existing = receiptMapper.findByUserAndCall(grant.userId(), callId);
            if (existing != null) {
                if (!grant.runId().equals(existing.getRunId())
                        || !MessageDigest.isEqual(existing.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                        requestHash.getBytes(StandardCharsets.US_ASCII))) {
                    throw AgentCommandException.conflict("CALL_ID_CONTENT_CONFLICT");
                }
                if (!grant.contentHash().equals(requestHash)) {
                    throw AgentCommandException.conflict("CALL_ID_CONTENT_CONFLICT");
                }
                return responseFrom(existing, true);
            }
            if (!MessageDigest.isEqual(grant.contentHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw AgentCommandException.forbidden();
            }

            RecordDTO dto = new RecordDTO();
            dto.setContent(content);
            dto.setRequestAiProcessing(false);
            RecordVO created = recordService.create(dto, run.getUserId());
            if (created == null || created.getId() == null || created.getStatus() == null
                    || !"record_only".equals(created.getStatus().getValue())) {
                throw AgentCommandException.unavailable();
            }
            AgentCommandResponse response = new AgentCommandResponse(callId, "succeeded", false,
                    new AgentCommandResponse.AgentCreatedRecord(created.getId(), created.getStatus().getValue(),
                            created.getCreatedAt()));
            AgentCommandReceipt receipt = AgentCommandReceipt.builder()
                    .userId(run.getUserId()).callId(callId).runId(run.getRunId())
                    .command("create_record").requestHash(requestHash)
                    .resultJson(objectMapper.writeValueAsString(response))
                    .createdAt(OffsetDateTime.now(ZONE)).build();
            if (receiptMapper.insert(receipt) != 1) throw AgentCommandException.unavailable();

            ToolCall audit = ToolCall.builder()
                    .userId(run.getUserId()).sessionId(run.getSessionId()).runId(run.getRunId())
                    .callId(callId.toString()).engine(run.getEngine()).tool("create_record")
                    .args(Map.of("content_sha256", requestHash, "slot", grant.slot()))
                    .resultSummary("create_record:" + created.getId()).success(true)
                    .latencyMs((int) Math.min((System.nanoTime() - started) / 1_000_000L, Integer.MAX_VALUE))
                    .code("OK").createdAt(OffsetDateTime.now(ZONE)).build();
            if (toolCallMapper.insert(audit) != 1) throw AgentCommandException.unavailable();
            return response;
        } catch (AgentCommandException error) {
            throw error;
        } catch (DataAccessException error) {
            log.warn("Agent create command database operation failed: runId={}, cause={}",
                    grant.runId(), error.getClass().getSimpleName());
            throw AgentCommandException.unavailable();
        } catch (Exception error) {
            log.warn("Agent create command failed: runId={}, cause={}",
                    grant.runId(), error.getClass().getSimpleName());
            throw AgentCommandException.unavailable();
        }
    }

    @Transactional(readOnly = true)
    public Optional<AgentCommandResponse> getReceipt(VerifiedGrant grant, UUID callId) {
        if (grant == null || callId == null) throw AgentCommandException.forbidden();
        validateGrantShape(grant, grant.runId(), callId);
        AgentCommandReceipt receipt = receiptMapper.findByUserAndCall(grant.userId(), callId);
        if (receipt == null || !grant.runId().equals(receipt.getRunId())) return Optional.empty();
        if (!grant.contentHash().equals(receipt.getRequestHash())) throw AgentCommandException.forbidden();
        return Optional.of(responseFrom(receipt, true));
    }

    /** Internal recovery lookup used by run finalization and status replay. */
    @Transactional(readOnly = true)
    public Optional<AgentCommandResponse> findReceiptForRun(UUID userId, UUID runId) {
        if (userId == null || runId == null) return Optional.empty();
        UUID callId = AgentWriteGrantService.callId(runId);
        AgentCommandReceipt receipt = receiptMapper.findByRunAndCall(userId, runId, callId);
        return receipt == null ? Optional.empty() : Optional.of(responseFrom(receipt, true));
    }

    private void validateGrantShape(VerifiedGrant grant, UUID requestRunId, UUID callId) {
        if (!"create_record".equals(grant.command()) || !"create_record:0".equals(grant.slot())
                || grant.requestAiProcessing() || !grant.runId().equals(requestRunId)
                || !AgentWriteGrantService.callId(grant.runId()).equals(callId)) {
            throw AgentCommandException.forbidden();
        }
        if (grant.expiresAt() == null || !grant.expiresAt().isAfter(OffsetDateTime.now(ZONE))) {
            throw AgentCommandException.unauthorized();
        }
    }

    private void validateLiveRun(VerifiedGrant grant, AgentRun run) {
        if (run == null) throw AgentCommandException.conflict("RUN_NOT_EXECUTABLE");
        if (!grant.userId().equals(run.getUserId()) || !grant.sessionId().equals(run.getSessionId())) {
            throw AgentCommandException.forbidden();
        }
        if (!"graph_v2".equals(run.getEngine()) || !"live".equals(run.getMode())
                || !"running".equals(run.getStatus()) || run.getLeaseExpiresAt() == null
                || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now(ZONE))) {
            throw AgentCommandException.conflict("RUN_NOT_EXECUTABLE");
        }
    }

    private AgentCommandResponse responseFrom(AgentCommandReceipt receipt, boolean replayed) {
        try {
            AgentCommandResponse stored = objectMapper.readValue(receipt.getResultJson(), AgentCommandResponse.class);
            return new AgentCommandResponse(stored.callId(), stored.status(), replayed, stored.record());
        } catch (Exception error) {
            throw AgentCommandException.unavailable();
        }
    }
}
