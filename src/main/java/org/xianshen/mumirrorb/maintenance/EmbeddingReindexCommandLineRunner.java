package org.xianshen.mumirrorb.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offline, per-user embedding-space migration. It stages all target vectors before one atomic database commit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mirror.embedding.reindex.enabled", havingValue = "true")
public class EmbeddingReindexCommandLineRunner implements ApplicationRunner {

    private static final int REQUIRED_DIMENSION = 1024;

    private static final String SELECT_DIARY_TARGETS = """
            SELECT c.id, c.segment AS embedding_text
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = ?::uuid
              AND c.vault_item_id IS NULL
              AND c.ai_memory_approved IS TRUE
              AND c.segment IS NOT NULL AND btrim(c.segment) <> ''
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.deleted_at IS NULL
            ORDER BY c.id
            """;

    private static final String SELECT_VAULT_TARGETS = """
            SELECT c.id, CASE WHEN c.segment IS NULL THEN c.content ELSE c.segment END AS embedding_text
            FROM chunks c
            JOIN vault_items v ON v.id = c.vault_item_id
            WHERE c.user_id = ?::uuid
              AND v.user_id = c.user_id
              AND v.digest_status = 'confirmed'
              AND v.deleted_at IS NULL
              AND btrim(COALESCE(CASE WHEN c.segment IS NULL THEN c.content ELSE c.segment END, '')) <> ''
            ORDER BY c.id
            """;

    private static final String UPDATE_DIARY_VECTOR = """
            UPDATE chunks c
            SET embedding = ?::vector
            WHERE c.id = ?
              AND c.user_id = ?::uuid
              AND c.segment = ?
              AND c.vault_item_id IS NULL
              AND c.ai_memory_approved IS TRUE
              AND EXISTS (
                  SELECT 1 FROM records r
                  WHERE r.id = c.record_id
                    AND r.user_id = c.user_id
                    AND r.source = 'user'
                    AND r.status = 'done'
                    AND r.deleted_at IS NULL
              )
            """;

    private static final String UPDATE_VAULT_VECTOR = """
            UPDATE chunks c
            SET embedding = ?::vector
            WHERE c.id = ?
              AND c.user_id = ?::uuid
              AND CASE WHEN c.segment IS NULL THEN c.content ELSE c.segment END = ?
              AND c.vault_item_id IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM vault_items v
                  WHERE v.id = c.vault_item_id
                    AND v.user_id = c.user_id
                    AND v.digest_status = 'confirmed'
                    AND v.deleted_at IS NULL
              )
            """;

    private static final String CLEAR_INELIGIBLE_VECTORS = """
            UPDATE chunks c
            SET embedding = NULL
            WHERE c.user_id = ?::uuid
              AND c.embedding IS NOT NULL
              AND NOT (
                  (
                      c.vault_item_id IS NULL
                      AND c.ai_memory_approved IS TRUE
                      AND c.segment IS NOT NULL AND btrim(c.segment) <> ''
                      AND EXISTS (
                          SELECT 1 FROM records r
                          WHERE r.id = c.record_id
                            AND r.user_id = c.user_id
                            AND r.source = 'user'
                            AND r.status = 'done'
                            AND r.deleted_at IS NULL
                      )
                  )
                  OR
                  (
                      c.vault_item_id IS NOT NULL
                      AND btrim(COALESCE(CASE WHEN c.segment IS NULL THEN c.content ELSE c.segment END, '')) <> ''
                      AND EXISTS (
                          SELECT 1 FROM vault_items v
                          WHERE v.id = c.vault_item_id
                            AND v.user_id = c.user_id
                            AND v.digest_status = 'confirmed'
                            AND v.deleted_at IS NULL
                      )
                  )
              )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SettingsMapper settingsMapper;
    private final AiGrpcClient aiGrpcClient;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${mirror.embedding.reindex.user-id:}")
    private String userIdValue;

    @Value("${mirror.embedding.reindex.expected-model:}")
    private String expectedModel;

    @Value("${mirror.embedding.reindex.target-model:}")
    private String targetModel;

    @Value("${mirror.embedding.reindex.apply:false}")
    private boolean apply;

    @Value("${mirror.embedding.reindex.offline-confirmed:false}")
    private boolean offlineConfirmed;

    @Value("${spring.main.web-application-type:servlet}")
    private String webApplicationType;

    @Override
    public void run(ApplicationArguments args) {
        UUID userId = requiredUuid(userIdValue, "mirror.embedding.reindex.user-id");
        requireValue(expectedModel, "mirror.embedding.reindex.expected-model");
        requireValue(targetModel, "mirror.embedding.reindex.target-model");
        if (expectedModel.equals(targetModel)) {
            throw new IllegalArgumentException("目标模型必须与当前模型不同");
        }

        UserSettings settings = loadSettings(userId);
        if (!"api".equalsIgnoreCase(settings.getEmbeddingSource())) {
            throw new IllegalStateException("该脚本只处理 API Embedding 配置");
        }
        if (!expectedModel.equals(settings.getEmbeddingModel())) {
            throw new IllegalStateException("当前模型与 expected-model 不一致；为避免覆盖配置，已停止");
        }
        if (settings.getEmbeddingApiKey() == null || settings.getEmbeddingApiKey().isBlank()) {
            throw new IllegalStateException("用户没有配置 Embedding API Key");
        }

        List<EmbeddingTarget> targets = loadTargets(userId);
        long diaryCount = targets.stream().filter(t -> t.kind() == TargetKind.DIARY).count();
        long vaultCount = targets.size() - diaryCount;
        if (!apply) {
            log.info("Embedding 重建 dry-run：userId={}, currentModel={}, targetModel={}, authorizedDiaryChunks={}, confirmedVaultChunks={}",
                    userId, expectedModel, targetModel, diaryCount, vaultCount);
            applicationContext.close();
            return;
        }

        if (!offlineConfirmed || !"none".equalsIgnoreCase(webApplicationType)) {
            throw new IllegalStateException("apply 模式要求 API 服务已停机，并以 spring.main.web-application-type=none 启动");
        }

        verifyTargetModel(userId);
        Map<Long, List<Float>> vectors = generateVectors(userId, targets);
        transactionTemplate.executeWithoutResult(status -> applyAtomically(userId, targets, vectors));

        log.info("Embedding 重建完成：userId={}, model={} -> {}, diaryChunks={}, confirmedVaultChunks={}, profileSnapshotsInvalidated={}",
                userId, expectedModel, targetModel, diaryCount, vaultCount, true);
        applicationContext.close();
    }

    private void verifyTargetModel(UUID userId) {
        EmbeddingProto.ModelInfoResponse info = aiGrpcClient.getModelInfo(userId, targetModel);
        if (!info.getAvailable() || info.getDimension() != REQUIRED_DIMENSION) {
            throw new IllegalStateException("目标模型不可用或维度不匹配；需要 1024 维，数据库没有修改");
        }
    }

    private Map<Long, List<Float>> generateVectors(UUID userId, List<EmbeddingTarget> targets) {
        Map<Long, List<Float>> vectors = new LinkedHashMap<>();
        for (EmbeddingTarget target : targets) {
            EmbeddingProto.EmbedResponse response = aiGrpcClient.embed(userId, target.text(), targetModel);
            if (response.getDimension() != REQUIRED_DIMENSION || response.getVectorCount() != REQUIRED_DIMENSION) {
                throw new IllegalStateException("目标模型返回了非 1024 维向量；数据库没有修改");
            }
            vectors.put(target.id(), List.copyOf(response.getVectorList()));
        }
        return vectors;
    }

    private void applyAtomically(UUID userId, List<EmbeddingTarget> targets, Map<Long, List<Float>> vectors) {
        jdbcTemplate.execute("LOCK TABLE user_settings, records, vault_items, chunks, profile_snapshots IN SHARE ROW EXCLUSIVE MODE");

        UserSettings currentSettings = loadSettings(userId);
        if (!"api".equalsIgnoreCase(currentSettings.getEmbeddingSource())
                || !expectedModel.equals(currentSettings.getEmbeddingModel())) {
            throw new IllegalStateException("生成向量期间用户 Embedding 配置发生变化；数据库事务已回滚");
        }

        List<EmbeddingTarget> currentTargets = loadTargets(userId);
        if (!targets.equals(currentTargets) || vectors.size() != targets.size()) {
            throw new IllegalStateException("生成向量期间已授权内容发生变化；数据库事务已回滚");
        }

        int cleared = jdbcTemplate.update(CLEAR_INELIGIBLE_VECTORS, userId.toString());
        for (EmbeddingTarget target : targets) {
            List<Float> vector = vectors.get(target.id());
            String literal = vectorLiteral(vector);
            int updated = target.kind() == TargetKind.DIARY
                    ? jdbcTemplate.update(UPDATE_DIARY_VECTOR, literal, target.id(), userId.toString(), target.text())
                    : jdbcTemplate.update(UPDATE_VAULT_VECTOR, literal, target.id(), userId.toString(), target.text());
            if (updated != 1) {
                throw new IllegalStateException("片段状态在提交时发生变化；数据库事务已回滚");
            }
        }

        // Existing profiles may have been derived from legacy, unauthorized diaries. Keep the text for history,
        // but make it unavailable to future AI context and reset vectors from the previous embedding space.
        int invalidatedProfiles = jdbcTemplate.update(
                "UPDATE profile_snapshots SET embedding = NULL, invalidated = TRUE WHERE user_id = ?::uuid",
                userId.toString());

        int settingUpdated = jdbcTemplate.update("""
                        UPDATE user_settings
                        SET embedding_model = ?, updated_at = now()
                        WHERE user_id = ?::uuid
                          AND embedding_source = 'api'
                          AND embedding_model = ?
                        """,
                targetModel, userId.toString(), expectedModel);
        if (settingUpdated != 1) {
            throw new IllegalStateException("用户模型配置未能安全切换；数据库事务已回滚");
        }

        log.info("Embedding 重建提交：userId={}, eligibleVectors={}, unauthorizedOrInactiveVectorsCleared={}, profilesInvalidated={}",
                userId, targets.size(), cleared, invalidatedProfiles);
    }

    private List<EmbeddingTarget> loadTargets(UUID userId) {
        List<EmbeddingTarget> targets = new ArrayList<>();
        targets.addAll(jdbcTemplate.query(SELECT_DIARY_TARGETS,
                (rs, rowNum) -> new EmbeddingTarget(rs.getLong("id"), TargetKind.DIARY,
                        rs.getString("embedding_text")), userId.toString()));
        targets.addAll(jdbcTemplate.query(SELECT_VAULT_TARGETS,
                (rs, rowNum) -> new EmbeddingTarget(rs.getLong("id"), TargetKind.VAULT,
                        rs.getString("embedding_text")), userId.toString()));
        targets.sort(Comparator.comparing(EmbeddingTarget::kind).thenComparingLong(EmbeddingTarget::id));
        return targets;
    }

    private UserSettings loadSettings(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(new LambdaQueryWrapper<UserSettings>()
                .eq(UserSettings::getUserId, userId));
        if (settings == null) {
            throw new IllegalStateException("指定用户不存在或没有 user_settings 配置");
        }
        return settings;
    }

    private static String vectorLiteral(List<Float> vector) {
        if (vector == null || vector.size() != REQUIRED_DIMENSION) {
            throw new IllegalStateException("待写入向量不是 1024 维");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            Float value = vector.get(i);
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalStateException("Embedding 返回了非法数值");
            }
            if (i > 0) literal.append(',');
            literal.append(value);
        }
        return literal.append(']').toString();
    }

    private static UUID requiredUuid(String value, String property) {
        requireValue(value, property);
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + " 必须是 UUID", e);
        }
    }

    private static void requireValue(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("必须设置 " + property);
        }
    }

    private enum TargetKind { DIARY, VAULT }

    private record EmbeddingTarget(long id, TargetKind kind, String text) {
    }
}
