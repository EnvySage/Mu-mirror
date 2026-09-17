package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.DailySummaryVO;

import java.util.List;
import java.util.UUID;

/**
 * 每日总结服务（设计文档 6.7，路线图阶段 5）
 *
 * <p>系统 Record 方案（裁决 #17）：总结 = Record(source='system', status='done')
 * + Chunk(metadata.contentType='daily_summary', metadata.summaryDate)，跳管道跳审核，直接 Embed。</p>
 */
public interface SummaryService {

    /**
     * 为指定用户生成昨日日报（定时任务逐用户调用；已存在则跳过，幂等）
     *
     * @return 生成的日报 VO；昨日无记录 / 未配置 LLM / 已存在时返回 null（跳过）
     */
    DailySummaryVO generateForUser(UUID userId);

    /**
     * 为指定用户生成指定日期的日报（补生成 / 重生成）
     *
     * <p>定时任务只跑"昨天"，某天生成失败就永远不会再补——这个重载用于：
     * ① 前端「重新生成」按钮；② 定时任务回溯补跑最近 N 天缺失的日报。</p>
     *
     * @param date  日报日期，必须是已经过去的日期
     * @param force true = 已有日报时先删旧再重建（重生成）；false = 已有则跳过（补生成，幂等）
     * @return 生成的日报 VO；无记录 / 未配置 LLM / 已存在且 force=false 时返回 null
     */
    DailySummaryVO generateForUser(UUID userId, java.time.LocalDate date, boolean force);

    /**
     * 列出"有记录但缺日报"的日期（补生成入口的数据源）
     *
     * <p>日报只有 01:00 一条生成路径，失败了不会留下任何痕迹——没有日报就是唯一的症状。
     * 前端据此在日报流里插入「未生成」行，把补生成按钮挂在缺失的那一天上，
     * 而不是挂在列表顶部（挂顶部既丑又要用户自己猜哪天缺）。</p>
     *
     * @param days 回溯天数（不含今天，1~30）
     * @return 缺失日期列表（yyyy-MM-dd，新→旧）
     */
    List<String> listMissingDates(UUID userId, int days);

    /**
     * 查询日报
     *
     * @param date 可空：为空返回日报列表（新→旧，不含 content）；传日期返回单篇（含 content）
     */
    List<DailySummaryVO> list(UUID userId, String date);

    /**
     * 查询日报（游标分页）
     *
     * <p>日报随使用天数无限累积，列表接口不再全量返回：前端默认只取最近 7 篇（侧栏口径），
     * 「全部」弹窗按 limit 分页 + before 游标向下翻，避免一次性拉全量。</p>
     *
     * @param date   可空：传日期时忽略 limit/before，返回该日单篇（含 content）
     * @param limit  每页条数（1~50，null 时取默认 20 安全上限）
     * @param before 游标：只返回 summary_date &lt; before 的日报（可空 = 从最新一篇开始）
     */
    List<DailySummaryVO> list(UUID userId, String date, Integer limit, String before);
}
