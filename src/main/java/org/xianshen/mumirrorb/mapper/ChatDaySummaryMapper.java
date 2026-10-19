package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.xianshen.mumirrorb.pojo.DO.ChatDaySummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ChatDaySummaryMapper extends BaseMapper<ChatDaySummary> {
    @Select("SELECT * FROM chat_day_summaries WHERE user_id=#{userId}::uuid ORDER BY source_date DESC")
    List<ChatDaySummary> listForUser(@Param("userId") UUID userId);

    @Select("SELECT * FROM chat_day_summaries WHERE user_id=#{userId}::uuid AND source_date=#{day}")
    ChatDaySummary find(@Param("userId") UUID userId, @Param("day") LocalDate day);

    @Update("""
      UPDATE chat_day_summaries SET state='PROCESSING', lease_until=now()+interval '2 minutes',
      version=version+1, error_code=NULL, updated_at=now()
      WHERE user_id=#{userId}::uuid AND source_date=#{day} AND source_hash=#{hash}
        AND (state='FAILED' OR (state='PROCESSING' AND lease_until < now()))""")
    int acquireRetry(@Param("userId") UUID userId, @Param("day") LocalDate day, @Param("hash") String hash);

    @Update("""
      UPDATE chat_day_summaries SET source_session_id=#{sessionId}::uuid,source_hash=#{hash},summary=NULL,
      state='PROCESSING',version=version+1,lease_until=now()+interval '2 minutes',error_code=NULL,updated_at=now()
      WHERE user_id=#{userId}::uuid AND source_date=#{day} AND source_hash<>#{hash}
        AND (state<>'PROCESSING' OR lease_until < now())""")
    int refreshSource(@Param("userId") UUID userId, @Param("day") LocalDate day,
                      @Param("sessionId") UUID sessionId, @Param("hash") String hash);

    @Insert("""
      INSERT INTO chat_day_summaries(user_id,source_date,source_session_id,source_hash,state,version,lease_until)
      VALUES(#{userId}::uuid,#{day},#{sessionId}::uuid,#{hash},'PROCESSING',1,now()+interval '2 minutes')
      ON CONFLICT(user_id,source_date) DO NOTHING""")
    int insertLease(@Param("userId") UUID userId, @Param("day") LocalDate day,
                    @Param("sessionId") UUID sessionId, @Param("hash") String hash);

    @Update("""
      UPDATE chat_day_summaries SET summary=#{summary},state='READY',lease_until=NULL,error_code=NULL,updated_at=now()
      WHERE user_id=#{userId}::uuid AND source_date=#{day} AND source_hash=#{hash} AND version=#{version} AND state='PROCESSING'""")
    int complete(@Param("userId") UUID userId, @Param("day") LocalDate day, @Param("hash") String hash,
                 @Param("version") long version, @Param("summary") String summary);

    @Update("""
      UPDATE chat_day_summaries SET state='FAILED',lease_until=NULL,error_code=#{error},updated_at=now()
      WHERE user_id=#{userId}::uuid AND source_date=#{day} AND source_hash=#{hash} AND version=#{version} AND state='PROCESSING'""")
    int fail(@Param("userId") UUID userId, @Param("day") LocalDate day, @Param("hash") String hash,
             @Param("version") long version, @Param("error") String error);

    @Update("""
      UPDATE chat_day_summaries SET summary=NULL,state='FAILED',version=version+1,lease_until=NULL,
        error_code='SOURCE_FORGOTTEN',updated_at=now()
      WHERE user_id=#{userId}::uuid AND source_date=#{day}
      """)
    int invalidateForForgottenSource(@Param("userId") UUID userId, @Param("day") LocalDate day);
}
