package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Vault 配置（toolcalling-vault-design.md 3.1：全部配置化）
 *
 * <p>application.yml 前缀 {@code vault.*}；默认值即设计稿首版参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vault")
public class VaultProperties {

    /** 单文件上限（字节），默认 20MB */
    private long maxFileSizeBytes = 20L * 1024 * 1024;

    /** 每用户总配额（字节），默认 500MB */
    private long quotaBytes = 500L * 1024 * 1024;

    /** 类型白名单（magic bytes 判定的 mime 前缀组） */
    private List<String> allowedTypes = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
            "text/plain", "text/markdown", "text/csv",
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/mpeg", "audio/wav", "audio/x-m4a", "audio/mp4");

    /** digest 全消化文本上限（token 近似=字符数/2，超限只索引前 N 章）；默认 5 万 token ≈ 10 万字符 */
    private int digestMaxChars = 100_000;

    /** PlanTools 单次规划超时（毫秒），设计稿 3s */
    private long planToolsTimeoutMs = 3_000;

    /** PlanTools 单次对话最多工具步数 */
    private int maxToolCalls = 2;

    /** find_item 结果上限 */
    private int findItemLimit = 10;

    /** search_records 结果上限（设计稿 limit≤20） */
    private int searchRecordsLimit = 20;
}
