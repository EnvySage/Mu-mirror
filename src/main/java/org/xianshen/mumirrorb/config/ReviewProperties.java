package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审核模式总闸配置（auto 审核软禁用）
 *
 * <p>application.yml 前缀 {@code review.*}；{@link #autoEnabled} 为总闸：</p>
 * <ul>
 *   <li>{@code false}（生产强制）→ 强制 manual：忽略 user_settings.review_mode 存量值，
 *       记录一律进人工审核流程；写入口拒绝把 reviewMode 置为 auto；GET /settings
 *       透出 {@code autoReviewAvailable=false}，前端隐藏 auto 开关。</li>
 *   <li>{@code true} → 恢复历史行为：读 user_settings.review_mode（缺省 manual），
 *       允许 auto 与前端开关。</li>
 * </ul>
 *
 * <p>软禁用：不改动/删除 review_mode 字段与 auto 分支代码，只加总闸。yml 显式配 false，
 * 类内默认 true 保证未配置时行为与历史一致（避免误伤测试/其他环境）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "review")
public class ReviewProperties {

    /** auto 审核模式总闸：false 时强制 manual（前端隐藏 auto 开关）。类内默认 true，由 yml 显式覆盖为 false */
    private boolean autoEnabled = true;
}
