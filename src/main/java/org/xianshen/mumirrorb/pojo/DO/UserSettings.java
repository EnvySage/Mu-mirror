package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户配置实体类（对应 user_settings 表）
 *
 * <p>每个用户一条配置记录，包含：</p>
 * <ul>
 *   <li>LLM 配置（AI 提供商、API Key、模型名等）</li>
 *   <li>Embedding 配置（本地/API 模式、模型名等）</li>
 *   <li>审核配置（手动/自动审核模式）</li>
 * </ul>
 *
 * <p><strong>配置更新流程：</strong></p>
 * <pre>
 * 用户改配置 → Java 存 user_settings 表
 *   → gRPC 调用时从数据库读取配置，放入请求中
 *   → Python 完全无状态
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_settings")
@Schema(description = "用户配置实体 - 对应 user_settings 表")
public class UserSettings {

    /**
     * 配置ID（UUID，由数据库 gen_random_uuid() 自动生成）
     */
    @TableId(type = IdType.AUTO)
    @Schema(description = "配置ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    /**
     * 关联用户ID（每个用户一条配置）
     */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    /**
     * AI 提供商
     *
     <p>可选值：openai / zhipu / qwen</p>
     */
    @Schema(description = "AI 提供商", example = "openai", allowableValues = {"openai", "zhipu", "qwen"})
    private String aiProvider;

    /**
     * AI API Key（加密存储）
     */
    @Schema(description = "AI API Key（加密存储）", example = "sk-xxx")
    private String aiApiKey;

    /**
     * AI API 地址（可选，留空使用默认地址）
     */
    @Schema(description = "AI API 地址（可选）", example = "https://api.openai.com/v1")
    private String aiBaseUrl;

    /**
     * AI 模型名称
     */
    @Schema(description = "AI 模型名称", example = "gpt-4o-mini")
    private String aiModel;

    /**
     * Embedding 来源
     *
     <p>local: 本地模型（BGE-m3），api: 远程 API</p>
     */
    @Schema(description = "Embedding 来源", example = "local", allowableValues = {"local", "api"})
    private String embeddingSource;

    /**
     * Embedding API Key（加密存储，仅 api 模式使用）
     */
    @Schema(description = "Embedding API Key（加密存储）")
    private String embeddingApiKey;

    /**
     * Embedding 模型名称
     */
    @Schema(description = "Embedding 模型名称", example = "BAAI/bge-m3")
    private String embeddingModel;

    /**
     * 审核模式
     *
     <p>manual: 手动审核（默认），auto: 自动审核</p>
     */
    @Schema(description = "审核模式", example = "manual", allowableValues = {"manual", "auto"})
    private String reviewMode;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
