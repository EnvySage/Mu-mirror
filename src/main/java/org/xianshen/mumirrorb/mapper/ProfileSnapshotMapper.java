package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * 画像快照 Mapper（设计文档 6.5）
 *
 * <p>分层保留查询与漂移检测（余弦距离）。不建向量索引：每用户仅 ~14 份快照，
 * 顺序扫描更快（裁决 #14），直接用 pgvector <=> 运算符。</p>
 */
@Mapper
public interface ProfileSnapshotMapper extends BaseMapper<ProfileSnapshot> {

    /**
     * 最新一份指定类型的快照
     *
     * <p>实现走 MP LambdaQueryWrapper（autoResultMap）：自定义 @Select 返回实体时
     * JSONB/vector 字段靠全局注册表自动映射，多个 List handler 竞争会选中错误的
     * Handler（E2E 联调实测 user_tags 解析崩溃），wrapper 查询按 @TableField
     * 的 typeHandler 精确映射。</p>
     *
     * @param userId       用户ID
     * @param snapshotType manual / monthly
     */
    default ProfileSnapshot selectLatest(java.util.UUID userId, String snapshotType) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                .eq(ProfileSnapshot::getUserId, userId)
                .eq(ProfileSnapshot::getSnapshotType, snapshotType)
                .orderByDesc(ProfileSnapshot::getCreatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 窗口内最新一份指定类型的快照（get_profile month 参数用，fix-batch B4）
     *
     * <p>月度归属口径（B4 任务书"createdAt 窗口近似+注释声明"）：period_month 列已由递归镜子轮
     * 落地（monthly 幂等精确列），但该列仅对 monthly 快照有值（manual 恒 NULL）——
     * manual 快照无归属月概念，仍走 created_at 窗口近似；monthly 优先走 period_month 精确归属。
     * 见 {@link #selectLatestInMonth(java.util.UUID, String, String, java.time.OffsetDateTime, java.time.OffsetDateTime)}。</p>
     */
    default ProfileSnapshot selectLatestInWindow(java.util.UUID userId, String snapshotType,
                                                 java.time.OffsetDateTime start, java.time.OffsetDateTime end) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                .eq(ProfileSnapshot::getUserId, userId)
                .eq(ProfileSnapshot::getSnapshotType, snapshotType)
                .ge(ProfileSnapshot::getCreatedAt, start)
                .lt(ProfileSnapshot::getCreatedAt, end)
                .orderByDesc(ProfileSnapshot::getCreatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 指定月份快照（get_profile month 参数，fix-batch B4）：
     * monthly 按 period_month 精确归属；manual 无归属月列，用 created_at 窗口近似（[start, end)）
     */
    default ProfileSnapshot selectLatestInMonth(java.util.UUID userId, String snapshotType,
                                                String periodMonth,
                                                java.time.OffsetDateTime start, java.time.OffsetDateTime end) {
        if ("monthly".equals(snapshotType)) {
            return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                    .eq(ProfileSnapshot::getUserId, userId)
                    .eq(ProfileSnapshot::getSnapshotType, snapshotType)
                    .eq(ProfileSnapshot::getPeriodMonth, periodMonth)
                    .orderByDesc(ProfileSnapshot::getCreatedAt)
                    .last("LIMIT 1"));
        }
        return selectLatestInWindow(userId, snapshotType, start, end);
    }

    /**
     * 最近 N 份指定类型的快照（对话 PROFILE 路由用 ≤2 份；漂移对比用）
     */
    default List<ProfileSnapshot> selectRecent(java.util.UUID userId, String snapshotType, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                .eq(ProfileSnapshot::getUserId, userId)
                .eq(ProfileSnapshot::getSnapshotType, snapshotType)
                .orderByDesc(ProfileSnapshot::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /**
     * 指定归属月份的 monthly 快照（rolling-mirror-design.md §4-B：幂等判断精确列）
     *
     * <p>period_month 为生成时写入的归属月份，替代旧"createdAt 落入定时窗口"的近似推断。</p>
     */
    default ProfileSnapshot selectByPeriodMonth(java.util.UUID userId, String periodMonth) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                .eq(ProfileSnapshot::getUserId, userId)
                .eq(ProfileSnapshot::getSnapshotType, "monthly")
                .eq(ProfileSnapshot::getPeriodMonth, periodMonth)
                .orderByDesc(ProfileSnapshot::getCreatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 指定用户全量快照（快照历史列表用，manual + monthly 合并，时间倒序）
     *
     * <p>上限 14 = manual 保 2 + monthly 保 12（分层保留后总量），无需分页。</p>
     */
    default List<ProfileSnapshot> selectAllByUser(java.util.UUID userId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                .eq(ProfileSnapshot::getUserId, userId)
                .orderByDesc(ProfileSnapshot::getCreatedAt)
                .last("LIMIT 14"));
    }

    /**
     * 漂移检测：本月快照 vs 上一份 monthly 快照的余弦距离（pgvector <=>）
     *
     * @return distance（0=完全相同，2=完全相反）；任一方 embedding 为 NULL 时返回 NULL
     */
    @Select("""
            SELECT cur.embedding <=> prev.embedding AS cosine_distance
            FROM profile_snapshots cur
            JOIN LATERAL (
                SELECT embedding FROM profile_snapshots
                WHERE user_id = cur.user_id::uuid
                  AND snapshot_type = 'monthly'
                  AND created_at < cur.created_at
                  AND embedding IS NOT NULL
                ORDER BY created_at DESC
                LIMIT 1
            ) prev ON true
            WHERE cur.id = #{snapshotId}
              AND cur.embedding IS NOT NULL
            """)
    Double selectDriftDistance(@Param("snapshotId") Long snapshotId);
}
