package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.pojo.VO.MirrorStatsVO;
import org.xianshen.mumirrorb.pojo.VO.SnapshotListVO;

import java.util.List;
import java.util.UUID;

/**
 * 镜子画像服务（设计文档 6.5，路线图阶段 3）
 */
public interface MirrorService {

    /**
     * 查询最新画像（优先 manual，无则 monthly）
     *
     * @return 最新快照 + 漂移信息；从未生成过画像时返回 VO（id=null）
     */
    MirrorProfileVO getMirror(UUID userId);

    /**
     * 快照历史列表（manual + monthly 合并全量，时间倒序，上限 14）
     *
     * <p>轻量 VO：id / snapshotType / createdAt / driftDistance / overallSummary（前 50 字截断）。
     * driftDistance 仅 monthly 快照计算（manual 无对比基线为 null）。</p>
     */
    List<SnapshotListVO> listSnapshots(UUID userId);

    /**
     * 单份完整快照（结构与 GET /api/mirror 的 MirrorProfileVO 一致 + id/snapshotType）
     *
     * @param snapshotId 快照ID
     * @param userId     当前用户（归属校验，不匹配按不存在处理）
     * @return 完整画像 VO（含漂移信息，逻辑与 getMirror 一致）
     */
    MirrorProfileVO getSnapshot(Long snapshotId, UUID userId);

    /**
     * 生成 manual 快照（用户点"查看镜子"触发）
     *
     * <p>流程：五维统计 SQL → recent_chats 组装 → gRPC GenerateProfile →
     * 存快照 + 五维拼接 Embed → 分层保留清理（manual 保 2 份）。</p>
     */
    MirrorProfileVO generate(UUID userId);

    /**
     * 按月生成 monthly 快照（历史月份回溯画像，如补生成 2026-08 的完整画像）
     *
     * <p>统计窗口为指定自然月（Asia/Shanghai），timeRange 传 "2026年8月" 形式；
     * month 为空默认上个月（与月度定时任务同语义）。幂等：同 (user, month) 已有
     * monthly 快照时重新生成则替换。校验：不允许当前月与未来月份
     * （当前月数据不完整走 manual 语义，未来月份无语料）。</p>
     *
     * @param month "yyyy-MM" 格式（如 "2026-08"）；null/空 = 上个月
     * @return 新生成的月度画像 VO
     */
    MirrorProfileVO generateMonthlyFor(UUID userId, String month);

    /**
     * 漂移检测：指定快照相对上一份 monthly 快照的余弦距离
     *
     * @return 距离（0~2）；无可比对象返回 null
     */
    Double driftDistance(Long snapshotId);

    /**
     * 镜子页图表统计（GET /api/mirror/stats?days=N）
     *
     * <p>五维统计底层数据：按日情绪 / 按日记录数 / 小时分布 / 星期分布（周一=0）/
     * 关键词 Top10 / 待办状态计数。窗口内缺失日期与桶由 Service 层补零。</p>
     *
     * @param userId 用户ID
     * @param days   统计窗口天数（调用方已 clamp 到 [7,90]）
     */
    MirrorStatsVO stats(UUID userId, int days);
}
