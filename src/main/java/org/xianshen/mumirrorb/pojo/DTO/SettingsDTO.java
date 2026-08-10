package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户配置请求 DTO（更新配置时使用）
 *
 * <p>所有字段都是可选的，只传需要修改的字段。</p>
 * <p>API Key 字段传明文，后端加密后存储。</p>
 */
@Data
@Schema(description = "用户配置请求DTO - 更新配置时使用")
public class SettingsDTO {

    /**
     * AI 提供商
     */
    @Schema(description = "AI 提供商", example = "openai", allowableValues = {"openai", "zhipu", "qwen"})
    private String aiProvider;

    /**
     * AI API Key（传明文，后端加密存储）
     */
    @Schema(description = "AI API Key（传明文，后端加密存储）", example = "sk-xxx")
    private String aiApiKey;

    /**
     * AI API 地址
     */
    @Schema(description = "AI API 地址（可选，留空使用默认）", example = "https://api.openai.com/v1")
    private String aiBaseUrl;

    /**
     * AI 模型名称
     */
    @Schema(description = "AI 模型名称", example = "gpt-4o-mini")
    private String aiModel;

    /**
     * Embedding 来源
     */
    @Schema(description = "Embedding 来源", example = "local", allowableValues = {"local", "api"})
    private String embeddingSource;

    /**
     * Embedding API Key（传明文，后端加密存储）
     */
    @Schema(description = "Embedding API Key（传明文，后端加密存储）")
    private String embeddingApiKey;

    /**
     * Embedding 模型名称
     */
    @Schema(description = "Embedding 模型名称", example = "BAAI/bge-m3")
    private String embeddingModel;

    /**
     * 审核模式
     */
    @Schema(description = "审核模式", example = "manual", allowableValues = {"manual", "auto"})
    private String reviewMode;
}
