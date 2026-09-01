package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.service.ChunkService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chunk 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements ChunkService {

    private final ChunkMapper chunkMapper;
    private final RecordMapper recordMapper;

    @Override
    @Transactional
    public ChunkVO update(Long chunkId, ChunkDTO dto, UUID userId) {
        // 1. 查询 Chunk
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "Chunk 不存在");
        }

        // 2. 验证所有权
        if (!chunk.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "Chunk 不存在");
        }

        // 3. 检查 Record 状态（只有 REVIEWING 允许修改）
        Record record = recordMapper.selectById(chunk.getRecordId());
        if (record == null || record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能修改 Chunk");
        }

        // 4. 更新 segment
        if (dto.getSegment() != null) {
            chunk.setSegment(dto.getSegment());
        }

        // 5. 更新 metadata（合并更新）
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        if (dto.getTitle() != null) {
            metadata.put("title", dto.getTitle());
        }
        if (dto.getSummary() != null) {
            metadata.put("summary", dto.getSummary());
        }
        if (dto.getContentType() != null) {
            metadata.put("contentType", dto.getContentType());
        }
        if (dto.getMood() != null) {
            metadata.put("mood", dto.getMood());
        }
        if (dto.getKeywords() != null) {
            metadata.put("keywords", dto.getKeywords());
        }

        chunk.setMetadata(metadata);
        chunkMapper.updateById(chunk);
        log.info("Chunk 已更新，ID: {}, 用户: {}", chunkId, userId);

        return toVO(chunk);
    }

    private ChunkVO toVO(Chunk chunk) {
        return ChunkVO.builder()
                .id(chunk.getId())
                .recordId(chunk.getRecordId())
                .segment(chunk.getSegment())
                .metadata(chunk.getMetadata())
                .hasEmbedding(chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty())
                .build();
    }
}
