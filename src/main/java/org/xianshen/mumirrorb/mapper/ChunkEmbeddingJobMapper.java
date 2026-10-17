package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ChunkEmbeddingJobMapper extends BaseMapper<ChunkEmbeddingJob> {

    @Insert("""
            INSERT INTO chunk_embedding_jobs(chunk_id, user_id, content_version, content_hash)
            VALUES (#{chunkId}, #{userId}::uuid, #{contentVersion}, #{contentHash})
            ON CONFLICT (chunk_id, content_version)
                WHERE status IN ('pending', 'processing')
            DO NOTHING
            """)
    int insertIfNoActiveVersion(@Param("chunkId") Long chunkId,
                                @Param("userId") UUID userId,
                                @Param("contentVersion") Long contentVersion,
                                @Param("contentHash") String contentHash);

    @Select("""
            WITH picked AS (
                SELECT id
                FROM chunk_embedding_jobs
                WHERE (status = 'pending' AND available_at <= NOW())
                   OR (status = 'processing' AND lease_until < NOW())
                ORDER BY available_at, id
                LIMIT #{limit}
                FOR UPDATE SKIP LOCKED
            )
            UPDATE chunk_embedding_jobs j
            SET status = 'processing', attempts = j.attempts + 1,
                lease_until = #{leaseUntil}, updated_at = NOW()
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.*
            """)
    List<ChunkEmbeddingJob> claimDue(@Param("limit") int limit,
                                     @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            UPDATE chunk_embedding_jobs
            SET status = 'ready', lease_until = NULL, last_error = NULL, updated_at = NOW()
            WHERE id = #{jobId} AND status = 'processing'
            """)
    int markReady(@Param("jobId") Long jobId);

    @Update("""
            UPDATE chunk_embedding_jobs
            SET status = #{status}, available_at = #{availableAt}, lease_until = NULL,
                last_error = #{error}, updated_at = NOW()
            WHERE id = #{jobId} AND status = 'processing'
            """)
    int finishAttempt(@Param("jobId") Long jobId,
                      @Param("status") String status,
                      @Param("availableAt") OffsetDateTime availableAt,
                      @Param("error") String error);

    @Update("""
            UPDATE chunk_embedding_jobs
            SET status = 'cancelled', lease_until = NULL, updated_at = NOW()
            WHERE chunk_id = #{chunkId} AND user_id = #{userId}::uuid
              AND status IN ('pending', 'processing')
            """)
    int cancelActive(@Param("chunkId") Long chunkId, @Param("userId") UUID userId);

    @Update("""
            UPDATE chunk_embedding_jobs
            SET status = 'pending', attempts = 0, available_at = NOW(), lease_until = NULL,
                last_error = NULL, updated_at = NOW()
            WHERE id = #{jobId} AND status = 'failed'
            """)
    int retryFailed(@Param("jobId") Long jobId);

    @Select("""
            SELECT * FROM chunk_embedding_jobs
            WHERE chunk_id = #{chunkId} AND user_id = #{userId}::uuid
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    ChunkEmbeddingJob findLatest(@Param("chunkId") Long chunkId, @Param("userId") UUID userId);
}
