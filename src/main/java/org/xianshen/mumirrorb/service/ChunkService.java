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
     * <p>改文本 → classified_segment 置 NULL（confirm 补分类判据）；改元数据 → 不影响它。
     * 任何编辑都置 user_edited=true。</p>
     *
     * @param chunkId Chunk ID
     * @param dto     更新数据
     * @param userId  当前用户ID（验证所有权）
     * @return 更新后的 Chunk
     */
    ChunkVO update(Long chunkId, ChunkDTO dto, UUID userId);

    /**
     * 删除 Chunk（审核阶段，设计文档 5.2 "删片段"）
     *
     * <p>仅 REVIEWING 状态允许；物理删除（Chunk 尚无下游依赖）。</p>
     *
     * @param chunkId Chunk ID
     * @param userId  当前用户ID（验证所有权）
     */
    void delete(Long chunkId, UUID userId);

    /**
     * 新增 Chunk（审核阶段手动片段，设计文档 5.2 "增片段"）
     *
     * <p>后端同步调用单段分类（single=true）回填 metadata，失败不阻断
     * （metadata 留空、classified_segment 为 NULL，confirm 时兜底重试）。</p>
     *
     * @param recordId    所属记录 ID
     * @param segmentText 用户手写的片段文本
     * @param userId      当前用户ID（验证所有权）
     * @return 新建的 Chunk（含 AI 回填的 metadata，如有）
     */
    ChunkVO add(Long recordId, String segmentText, UUID userId);
}
