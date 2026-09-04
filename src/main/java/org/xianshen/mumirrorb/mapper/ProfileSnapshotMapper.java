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
     * @param userId       用户ID
     * @param snapshotType manual / monthly
     */
    @Select("""
            SELECT * FROM profile_snapshots
            WHERE user_id = #{userId}::uuid
              AND snapshot_type = #{snapshotType}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    ProfileSnapshot selectLatest(@Param("userId") UUID userId,
                                 @Param("snapshotType") String snapshotType);

    /**
     * 最近 N 份指定类型的快照（对话 PROFILE 路由用 ≤2 份；漂移对比用）
     */
    @Select("""
            SELECT * FROM profile_snapshots
            WHERE user_id = #{userId}::uuid
              AND snapshot_type = #{snapshotType}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ProfileSnapshot> selectRecent(@Param("userId") UUID userId,
                                       @Param("snapshotType") String snapshotType,
                                       @Param("limit") int limit);

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
