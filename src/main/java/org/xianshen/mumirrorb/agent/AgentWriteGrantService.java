package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.config.ChatEngineProperties;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Signs a bounded, single-use command grant using a Java-only HMAC secret. */
@Service
@RequiredArgsConstructor
public class AgentWriteGrantService {

    private static final String[] PREFIXES = {
            "帮我记一篇日记：", "帮我记一篇日记:",
            "帮我创建一篇日记：", "帮我创建一篇日记:",
            "帮我保存一条记录：", "帮我保存一条记录:"
    };
    private static final String SLOT = "create_record:0";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentProperties agentProperties;
    private final ChatEngineProperties chatEngineProperties;
    private final ObjectMapper objectMapper;

    public Optional<CreateGrant> authorizeCreate(String question, AgentRun run) {
        if (!chatEngineProperties.isCreateRecordEnabled() || !isEligibleRun(run) || !isKeyConfigured()) {
            return Optional.empty();
        }
        String content = authorizedContent(question);
        if (content == null || content.isBlank() || content.length() > 2000) return Optional.empty();

        String hash = sha256(content);
        OffsetDateTime expiresAt = run.getStartedAt().plusNanos(agentProperties.getTotalBudgetMs() * 1_000_000L);
        if (!expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) return Optional.empty();
        VerifiedGrant claims = new VerifiedGrant(run.getUserId(), run.getSessionId(), run.getRunId(),
                "create_record", SLOT, hash, false, expiresAt);
        return Optional.of(new CreateGrant(sign(claims), content, callId(run.getRunId()), hash, expiresAt));
    }

    public VerifiedGrant verify(String token) {
        if (!isKeyConfigured() || token == null || token.isBlank()) throw AgentCommandException.unauthorized();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw AgentCommandException.unauthorized();
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] payload = decoder.decode(parts[0]);
            byte[] suppliedMac = decoder.decode(parts[1]);
            if (!MessageDigest.isEqual(mac(payload), suppliedMac)) throw AgentCommandException.unauthorized();
            Claims claims = objectMapper.readValue(payload, Claims.class);
            VerifiedGrant verified = new VerifiedGrant(
                    UUID.fromString(claims.userId()), UUID.fromString(claims.sessionId()), UUID.fromString(claims.runId()),
                    claims.command(), claims.slot(), claims.contentHash(), claims.requestAiProcessing(),
                    OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(claims.expiresAtEpochMs()), ZoneOffset.UTC));
            if (!"create_record".equals(verified.command()) || !SLOT.equals(verified.slot())
                    || verified.requestAiProcessing() || !verified.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                    || !verified.contentHash().matches("[0-9a-f]{64}")) {
                throw AgentCommandException.unauthorized();
            }
            return verified;
        } catch (AgentCommandException error) {
            throw error;
        } catch (Exception error) {
            throw AgentCommandException.unauthorized();
        }
    }

    public static String authorizedContent(String question) {
        if (question == null) return null;
        for (String prefix : PREFIXES) {
            if (question.startsWith(prefix)) {
                return question.substring(prefix.length()).trim().replace("\r\n", "\n");
            }
        }
        return null;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** UUIDv5(namespace=runId, name="create_record:0"), matching Python uuid.uuid5. */
    public static UUID callId(UUID runId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespace = ByteBuffer.allocate(16)
                    .putLong(runId.getMostSignificantBits()).putLong(runId.getLeastSignificantBits());
            digest.update(namespace.array());
            byte[] hash = digest.digest(SLOT.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (Exception error) {
            throw new IllegalStateException("SHA-1 unavailable", error);
        }
    }

    private boolean isEligibleRun(AgentRun run) {
        return run != null && run.getRunId() != null && run.getUserId() != null && run.getSessionId() != null
                && run.getStartedAt() != null && "graph_v2".equals(run.getEngine())
                && "live".equals(run.getMode()) && "running".equals(run.getStatus())
                && run.getLeaseExpiresAt() != null && run.getLeaseExpiresAt().isAfter(OffsetDateTime.now());
    }

    private boolean isKeyConfigured() {
        String key = agentProperties.getCommandHmacKey();
        return key != null && key.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private String sign(VerifiedGrant grant) {
        try {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            Claims claims = new Claims(grant.userId().toString(), grant.sessionId().toString(), grant.runId().toString(),
                    grant.command(), grant.slot(), grant.contentHash(), grant.requestAiProcessing(),
                    grant.expiresAt().toInstant().toEpochMilli(), Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
            byte[] payload = objectMapper.writeValueAsBytes(claims);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(mac(payload));
        } catch (Exception error) {
            throw new IllegalStateException("Could not issue create command grant", error);
        }
    }

    private byte[] mac(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(agentProperties.getCommandHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private record Claims(String userId, String sessionId, String runId, String command, String slot,
                          String contentHash, boolean requestAiProcessing, long expiresAtEpochMs, String nonce) { }
}
