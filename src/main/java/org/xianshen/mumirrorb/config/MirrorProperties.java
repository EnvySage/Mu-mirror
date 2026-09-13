package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 递归累计镜子配置（rolling-mirror-design.md §2/§3：四道防洪闸全配置化）
 *
 * <p>application.yml 前缀 {@code mirror.*}；默认值即设计稿定稿参数。
 * 闸门触发语义：条数闸与总字符闸先触发者生效，旧→新裁剪直到塞下；
 * 触发时日志 WARN + 前端 toast 提示"已截取最近部分"（meta 透出 {@link #truncationMetaKey}）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mirror")
public class MirrorProperties {

    /** 条数闸：回看原文最多带 N 条 chunk，超限取最近的。默认 600 */
    private int lookbackMaxChunks = 600;

    /** 总字符闸：回看原文总字符上限（与条数闸先触发者生效），旧→新裁剪直到塞下。默认 15 万 */
    private int lookbackMaxChars = 150_000;

    /** 单条日记渲染截断（超长日记只带前 N 字符）。默认 2000 */
    private int perChunkMaxChars = 2_000;

    /** 递归链压缩：距目标月超过 N 个月的更早镜子只带一行摘要。默认 12 */
    private int mirrorSummaryAfterMonths = 12;

    /**
     * 对话检索相关性下限（余弦距离，越小越严）：距离超过该值的 chunk 直接丢弃，
     * 全部被丢弃时走"没有找到相关记录"兜底——宁可不答，也不把噪声喂给 LLM 让它编。
     *
     * <p>0.35 ≈ 相似度 0.65（严格档）；放宽到 0.45/0.55 可提高召回。
     * ≤ 0 表示关闭阈值（回退旧行为：永远取 top-N）。</p>
     */
    private double ragMaxCosineDistance = 0.35;

    /** 生成接口 meta 透出字段名（截断发生时提示前端 toast，F 侧按此字段接线） */
    public static final String TRUNCATED_FLAG = "lookback_truncated";

    /** meta 透出用的 key（F 未接线前仅日志声明，不破坏现有响应结构） */
    public static final String TRUNCATION_META_KEY = "lookbackTruncated";
}
