package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.SettingsDTO;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;
import org.xianshen.mumirrorb.service.SettingsService;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    /** Embedding 维度硬约束（设计文档 3.4，裁决 #18） */
    private static final int EMBEDDING_DIMENSION = 1024;

    private final SettingsMapper settingsMapper;
    private final AiGrpcClient aiGrpcClient;

    @Override
    @Transactional(readOnly = true)
    public SettingsVO getSettings(UUID userId) {
        UserSettings settings = getOrCreateSettings(userId);
        return toVO(settings);
    }

    @Override
    @Transactional
    public SettingsVO updateSettings(SettingsDTO dto, UUID userId) {
        UserSettings settings = getOrCreateSettings(userId);

        // 只更新非 null 的字段（部分更新）
        if (dto.getAiProvider() != null) {
            settings.setAiProvider(dto.getAiProvider());
        }
        if (dto.getAiProtocol() != null) {
            settings.setAiProtocol(dto.getAiProtocol());
        }
        if (dto.getAiApiKey() != null) {
            settings.setAiApiKey(CryptoUtils.encrypt(dto.getAiApiKey()));
        }
        if (dto.getAiBaseUrl() != null) {
            settings.setAiBaseUrl(dto.getAiBaseUrl());
        }
        if (dto.getAiModel() != null) {
            settings.setAiModel(dto.getAiModel());
        }
        if (dto.getEmbeddingSource() != null) {
            settings.setEmbeddingSource(dto.getEmbeddingSource());
        }
        if (dto.getEmbeddingBaseUrl() != null) {
            settings.setEmbeddingBaseUrl(dto.getEmbeddingBaseUrl());
        }
        if (dto.getEmbeddingApiKey() != null) {
            settings.setEmbeddingApiKey(CryptoUtils.encrypt(dto.getEmbeddingApiKey()));
        }
        if (dto.getEmbeddingModel() != null) {
            settings.setEmbeddingModel(dto.getEmbeddingModel());
        }
        if (dto.getReviewMode() != null) {
            settings.setReviewMode(dto.getReviewMode());
        }
        if (dto.getRagHalfLife() != null) {
            settings.setRagHalfLife(dto.getRagHalfLife());
        }
        if (dto.getMirrorLookback() != null) {
            // 回看深度档位合法性防御（0-3，rolling-mirror-design.md §2）：越界值归默认 1
            int v = dto.getMirrorLookback();
            settings.setMirrorLookback(v >= 0 && v <= 3 ? v : 1);
        }

        settings.setUpdatedAt(OffsetDateTime.now());
        settingsMapper.updateById(settings);
        log.info("用户配置已更新，用户: {}", userId);

        return toVO(settings);
    }

    @Override
    @Transactional(readOnly = true)
    public String testAiConnection(UUID userId) {
        UserSettings settings = getSettingsEntity(userId);

        // 检查是否配置了 AI
        if (settings.getAiProvider() == null || settings.getAiApiKey() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先配置 AI 提供商和 API Key");
        }

        String apiKey = CryptoUtils.decrypt(settings.getAiApiKey());
        String maskedKey = CryptoUtils.mask(apiKey);

        // 真实探测：走 gRPC GetModelInfo 探测 AI 服务可达性（Python 端健康检查端点，9.2 #9）
        try {
            aiGrpcClient.getModelInfo(userId);
        } catch (Exception e) {
            log.error("AI 服务连接测试失败，用户: {}", userId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "AI 服务不可达: " + e.getMessage() + "（请确认 Python AI 服务已启动）");
        }

        log.info("测试 AI 连接成功，用户: {}, provider: {}, model: {}", userId, settings.getAiProvider(), settings.getAiModel());
        return String.format("连接正常。提供商: %s, 模型: %s, API Key: %s",
                settings.getAiProvider(),
                settings.getAiModel() != null ? settings.getAiModel() : "未设置",
                maskedKey);
    }

    /**
     * 测试 Embedding 连接 + 维度校验（设计文档 3.4，裁决 #18）
     *
     * <p>调 gRPC GetModelInfo 校验维度是否 1024；非 1024 拒绝并提示。
     * 2026-09-04 起 ModelInfoRequest 携带 EmbeddingConfig（field 1，见 shared-protocol.md），
     * api 模式下 Python 按用户配置的模型返回维度，校验语义完整。</p>
     */
    public String testEmbeddingConnection(UUID userId) {
        UserSettings settings = getSettingsEntity(userId);
        if ("api".equals(settings.getEmbeddingSource()) && settings.getEmbeddingApiKey() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先配置 Embedding API Key");
        }

        try {
            var info = aiGrpcClient.getModelInfo(userId);
            if (!info.getAvailable()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "Embedding 模型不可用: " + info.getModelName());
            }
            if (info.getDimension() != EMBEDDING_DIMENSION) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "当前版本仅支持 1024 维模型（当前 " + info.getDimension() + " 维: "
                                + info.getModelName() + "）");
            }
            log.info("测试 Embedding 连接成功，用户: {}, model: {}, dimension: {}",
                    userId, info.getModelName(), info.getDimension());
            return String.format("连接正常。模型: %s, 来源: %s, 维度: %d",
                    info.getModelName(), info.getSource(), info.getDimension());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Embedding 连接测试失败，用户: {}", userId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "AI 服务不可达: " + e.getMessage() + "（请确认 Python AI 服务已启动）");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String testDbConnection() {
        try {
            settingsMapper.selectCount(null);
            log.info("数据库连接测试成功");
            return "数据库连接正常";
        } catch (Exception e) {
            log.error("数据库连接测试失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "数据库连接失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户配置（不存在则创建空配置）
     */
    private UserSettings getOrCreateSettings(UUID userId) {
        UserSettings settings = getSettingsEntity(userId);
        if (settings == null) {
            settings = createEmptySettings(userId);
        }
        return settings;
    }

    /**
     * 获取用户配置（不自动创建）
     */
    private UserSettings getSettingsEntity(UUID userId) {
        return settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>()
                        .eq(UserSettings::getUserId, userId)
        );
    }

    /**
     * 创建空的用户配置
     */
    private UserSettings createEmptySettings(UUID userId) {
        UserSettings settings = UserSettings.builder()
                .userId(userId)
                .aiProtocol("anthropic")
                .embeddingSource("local")
                .reviewMode("manual")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        settingsMapper.insert(settings);
        log.info("已为用户创建空配置，用户: {}", userId);
        return settings;
    }

    /**
     * DO → VO 转换（API Key 脱敏）
     */
    private SettingsVO toVO(UserSettings settings) {
        return SettingsVO.builder()
                .id(settings.getId())
                .userId(settings.getUserId())
                .aiProvider(settings.getAiProvider())
                .aiProtocol(settings.getAiProtocol())
                .aiApiKey(settings.getAiApiKey() != null ? CryptoUtils.mask(CryptoUtils.decrypt(settings.getAiApiKey())) : null)
                .aiBaseUrl(settings.getAiBaseUrl())
                .aiModel(settings.getAiModel())
                .embeddingSource(settings.getEmbeddingSource())
                .embeddingBaseUrl(settings.getEmbeddingBaseUrl())
                .embeddingApiKey(settings.getEmbeddingApiKey() != null ? CryptoUtils.mask(CryptoUtils.decrypt(settings.getEmbeddingApiKey())) : null)
                .embeddingModel(settings.getEmbeddingModel())
                .reviewMode(settings.getReviewMode())
                .ragHalfLife(settings.getRagHalfLife())
                .mirrorLookback(settings.getMirrorLookback() == null ? 1 : settings.getMirrorLookback())
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}
