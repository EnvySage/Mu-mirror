package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户配置视图对象（返回给前端）
 *
 * <p>API Key 字段返回脱敏后的值（只显示前 3 位 + ***），不返回明文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户配置视图对象 - 返回给前端")
public class SettingsVO {

    @Schema(description = "配置ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "关联用户ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    @Schema(description = "AI 提供商", example = "openai")
    private String aiProvider;

    @Schema(description = "AI 模型协议", example = "anthropic")
    private String aiProtocol;

    /**
     * 脱敏后的 AI API Key
     *
     <p>示例：sk-xxx → sk-***</p>
     */
    @Schema(description = "AI API Key（脱敏）", example = "sk-***")
    private String aiApiKey;

    @Schema(description = "AI API 地址", example = "https://api.openai.com/v1")
    private String aiBaseUrl;

    @Schema(description = "AI 模型名称", example = "gpt-4o-mini")
    private String aiModel;

    @Schema(description = "Embedding 来源", example = "local")
    private String embeddingSource;

    /**
     * 脱敏后的 Embedding API Key
     */
    @Schema(description = "Embedding API Key（脱敏）")
    private String embeddingApiKey;

    @Schema(description = "Embedding 模型名称", example = "BAAI/bge-m3")
    private String embeddingModel;

    @Schema(description = "审核模式", example = "manual")
    private String reviewMode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间", example = "2026-08-07 14:30:00")
    private OffsetDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间", example = "2026-08-07 15:45:00")
    private OffsetDateTime updatedAt;
}
