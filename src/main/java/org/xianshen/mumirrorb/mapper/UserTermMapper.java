package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户个人词典 Mapper（lexicon-design.md v1.0）
 *
 * <p>注入排序/分组列表走 default 方法（LambdaQueryWrapper + autoResultMap，
 * 规避自定义 @Select 对 JSONB 列多 Handler 竞争坑，见 ProfileSnapshotMapper 教训）；
 * 唯一自定义 SQL 是近 30 天相关记录计数（只取标量列，无 JSONB 实体映射）。</p>
 */
@Mapper
public interface UserTermMapper extends BaseMapper<UserTerm> {

    /**
     * confirmed 词条按 query_hit_count 倒序（注入 top N 用；N=30 上限在 SQL 截断）
     */
    default List<UserTerm> selectConfirmedTop(UUID userId, int limit) {
        return selectList(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getStatus, "confirmed")
                .orderByDesc(UserTerm::getQueryHitCount)
                .orderByDesc(UserTerm::getLastConfirmedAt)
                .last("LIMIT " + limit));
    }

    /**
     * 用户全量词条（分组列表 / 抽取去重依据；按更新时间倒序）
     */
    default List<UserTerm> selectByUser(UUID userId) {
        return selectList(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .orderByDesc(UserTerm::getUpdatedAt));
    }

    /**
     * 近 30 天相关记录数（confirmed 卡片"近30天相关记录n条"）
     *
     * <p>对 segment/content 做 ILIKE 模糊匹配（term + 别名任一命中即算），
     * 仅 COUNT 标量列，不走实体映射。</p>
     *
     * @param userId   用户ID
     * @param patterns ILIKE 模式列表（Java 侧拼好 '%词%'，至少 1 个）
     * @param since    统计起点
     */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT r.id) AS cnt
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at &gt;= #{since}
              AND <foreach collection="patterns" item="p" open="(" separator=" OR " close=")">
                  (c.segment ILIKE #{p} OR c.content ILIKE #{p})
              </foreach>
            </script>
            """)
    Integer countRecentRecordHits(@Param("userId") UUID userId,
                                  @Param("patterns") List<String> patterns,
                                  @Param("since") OffsetDateTime since);
}
