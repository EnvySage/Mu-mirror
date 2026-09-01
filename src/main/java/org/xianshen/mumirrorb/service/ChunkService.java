package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;

import java.util.UUID;

/**
 * Chunk 服务接口
 *
 * <p>审核阶段用户可修改 Chunk 的 segment 和 metadata。</p>
 */
public interface ChunkService {

    /**
     * 更新 Chunk（审核阶段）
     *
     * @param chunkId Chunk ID
     * @param dto     更新数据
     * @param userId  当前用户ID（验证所有权）
     * @return 更新后的 Chunk
     */
    ChunkVO update(Long chunkId, ChunkDTO dto, UUID userId);
}
