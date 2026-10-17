package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("chunk_embedding_jobs")
public class ChunkEmbeddingJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chunkId;
    private UUID userId;
    private Long contentVersion;
    private String contentHash;
    private String status;
    private Integer attempts;
    private OffsetDateTime availableAt;
    private OffsetDateTime leaseUntil;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
