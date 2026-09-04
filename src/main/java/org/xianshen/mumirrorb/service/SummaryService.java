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
     * 查询日报
     *
     * @param date 可空：为空返回全部日报列表（新→旧，不含 content）；传日期返回单篇（含 content）
     */
    List<DailySummaryVO> list(UUID userId, String date);
}
