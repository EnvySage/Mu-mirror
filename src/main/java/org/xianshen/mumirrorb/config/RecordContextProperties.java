package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分类语境注入配置（近 7 天记录摘要，指代消解）
 *
 * <p>application.yml 前缀 {@code record-context.*}；默认值即设计定稿参数。
 * B 侧查 chunks.metadata 组装近期语境条目（date/title/keywords）随 ClassifyRequest 携带，
 * Python 侧只渲染不查库（无状态铁律不变）。</p>
 *
 * <p>组装失败（SQL 异常 / JSON 解析异常）时降级为空清单，绝不阻断分类。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "record-context")
public class RecordContextProperties {

    /** 近 N 天窗口（按 records.created_at）。默认 7 */
    private int windowDays = 7;

    /** 最终注入条数上限（Java 侧按 title 去重后取最近 N 条）。默认 20 */
    private int maxHints = 20;

    /** SQL 查询上限（去重前，多查一些兜住同 title 重复）。默认 50 */
    private int maxQuery = 50;

    /** 每条条目携带的 keywords 上限。默认 5 */
    private int maxKeywords = 5;
}
