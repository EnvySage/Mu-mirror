package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.Record;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录 Mapper
 */
@Mapper
public interface RecordMapper extends BaseMapper<Record> {

    /**
     * 查询指定月份内每天的有效记录数（用于日历标记）
     *
     * @param userId     用户ID
     * @param monthStart 月份开始时间
     * @param monthEnd   月份结束时间
     * @return 每行包含 date（字符串如 "2026-08-12"）和 cnt（记录数）
     */
    @Select("""
            SELECT TO_CHAR(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS date,
                   COUNT(*) AS cnt
            FROM records
            WHERE user_id = #{userId}::uuid
              AND deleted_at IS NULL
              AND status != 'failed'
              AND created_at >= #{monthStart}
              AND created_at < #{monthEnd}
            GROUP BY TO_CHAR(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD')
            """)
    List<Map<String, Object>> countByDay(@Param("userId") UUID userId,
                                         @Param("monthStart") OffsetDateTime monthStart,
                                         @Param("monthEnd") OffsetDateTime monthEnd);
}
