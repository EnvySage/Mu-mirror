package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.ChatSourceExclusion;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ChatSourceExclusionMapper {
    @Insert("""
      <script>INSERT INTO chat_source_exclusions(user_id,source_kind,source_id,source_version,source_fingerprint,reason)
      VALUES <foreach collection='rows' item='r' separator=','>
      (#{r.userId}::uuid,#{r.sourceKind},#{r.sourceId},#{r.sourceVersion},#{r.sourceFingerprint},#{r.reason})
      </foreach> ON CONFLICT(user_id,source_kind,source_id) DO NOTHING</script>
      """)
    int insertAll(@Param("rows") List<ChatSourceExclusion> rows);

    @Select("SELECT source_id FROM chat_source_exclusions WHERE user_id=#{userId}::uuid AND source_kind=#{kind} ORDER BY source_id")
    List<Long> listSourceIds(@Param("userId") UUID userId, @Param("kind") String kind);

    @Select("SELECT source_id FROM chat_source_exclusions WHERE user_id=#{userId}::uuid AND source_kind='chat_message' AND source_id IN (SELECT id FROM conversation_history WHERE session_id=#{sessionId}::uuid)")
    List<Long> listExcludedForSession(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);
}
