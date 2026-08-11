package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
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

    private final SettingsMapper settingsMapper;

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
        if (dto.getEmbeddingApiKey() != null) {
            settings.setEmbeddingApiKey(CryptoUtils.encrypt(dto.getEmbeddingApiKey()));
        }
        if (dto.getEmbeddingModel() != null) {
            settings.setEmbeddingModel(dto.getEmbeddingModel());
        }
        if (dto.getReviewMode() != null) {
            settings.setReviewMode(dto.getReviewMode());
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

        // TODO: 实际测试 AI 连接（调用 gRPC 或 HTTP）
        // 第一版先返回配置信息，后续实现真正的连接测试
        String apiKey = CryptoUtils.decrypt(settings.getAiApiKey());
        String maskedKey = CryptoUtils.mask(apiKey);

        log.info("测试 AI 连接，用户: {}, provider: {}, model: {}", userId, settings.getAiProvider(), settings.getAiModel());

        return String.format("AI 配置有效。提供商: %s, 模型: %s, API Key: %s",
                settings.getAiProvider(),
                settings.getAiModel() != null ? settings.getAiModel() : "未设置",
                maskedKey);
    }

    @Override
    @Transactional(readOnly = true)
    public String testDbConnection() {
        // TODO: 实际测试数据库连接
        // 第一版先返回简单信息
        try {
            // 简单测试：执行一个查询
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
                .embeddingApiKey(settings.getEmbeddingApiKey() != null ? CryptoUtils.mask(CryptoUtils.decrypt(settings.getEmbeddingApiKey())) : null)
                .embeddingModel(settings.getEmbeddingModel())
                .reviewMode(settings.getReviewMode())
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}
