package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.pipeline.ClassifyItemConverter;
import org.xianshen.mumirrorb.service.ChunkService;

import java.time.OffsetDateTime;
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
    private final AiGrpcClient aiGrpcClient;

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

        boolean textChanged = false;
        // 4. 更新 segment
        if (dto.getSegment() != null && !dto.getSegment().equals(chunk.getSegment())) {
            chunk.setSegment(dto.getSegment());
            textChanged = true;
        }

        // 5. 更新 metadata（合并更新）
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        boolean metadataChanged = false;
        if (dto.getTitle() != null) {
            metadata.put("title", dto.getTitle());
            metadataChanged = true;
        }
        if (dto.getSummary() != null) {
            metadata.put("summary", dto.getSummary());
            metadataChanged = true;
        }
        if (dto.getContentType() != null) {
            metadata.put("contentType", dto.getContentType());
            metadataChanged = true;
        }
        if (dto.getMood() != null) {
            metadata.put("mood", dto.getMood());
            metadataChanged = true;
        }
        if (dto.getKeywords() != null) {
            metadata.put("keywords", dto.getKeywords());
            metadataChanged = true;
        }

        // 6. classified_segment 状态机 + userEdited（设计文档 5.3）：
        //    改文本 → classified_segment 置 NULL（confirm 时补分类）；改元数据 → 不影响它
        if (textChanged) {
            chunk.setClassifiedSegment(null);
        }
        if (textChanged || metadataChanged) {
            chunk.setUserEdited(true);
        }

        chunk.setMetadata(metadata);
        chunkMapper.updateById(chunk);
        log.info("Chunk 已更新，ID: {}, 用户: {}, 文本变更: {}, 元数据变更: {}",
                chunkId, userId, textChanged, metadataChanged);

        return toVO(chunk);
    }

    @Override
    @Transactional
    public void delete(Long chunkId, UUID userId) {
        // 1. 查询 Chunk
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "Chunk 不存在");
        }

        // 2. 验证所有权
        if (!chunk.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "Chunk 不存在");
        }

        // 3. 检查 Record 状态（只有 REVIEWING 允许删除片段）
        Record record = recordMapper.selectById(chunk.getRecordId());
        if (record == null || record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能删除 Chunk");
        }

        chunkMapper.deleteById(chunkId);
        log.info("Chunk 已删除，ID: {}, 记录ID: {}, 用户: {}", chunkId, chunk.getRecordId(), userId);
    }

    @Override
    @Transactional
    public ChunkVO add(Long recordId, String segmentText, UUID userId) {
        // 1. 查询 Record 并校验
        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );
        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }
        if (record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能新增片段");
        }
        if (segmentText == null || segmentText.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "片段内容不能为空");
        }

        // 2. 创建 Chunk（设计文档 5.2）：classified_segment 初始 NULL（confirm 时兜底补分类）
        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(recordId)
                .content(record.getContent())
                .segment(segmentText)
                .classifiedSegment(null)
                .userEdited(true)
                .createdAt(OffsetDateTime.now())
                .build();

        // 3. 同步调用单段分类（single=true）回填 metadata；失败不阻断（metadata 留空）
        try {
            var response = aiGrpcClient.classifySingle(userId, segmentText);
            if (!response.getSkip() && response.getItemsCount() > 0) {
                Map<String, Object> metadata = ClassifyItemConverter.toMetadata(response.getItems(0));
                chunk.setMetadata(metadata);
                // AI 刚按当前文本分类过：写入当时文本，confirm 时无需再补
                chunk.setClassifiedSegment(segmentText);
                log.info("新增片段分类回填成功，记录ID: {}", recordId);
            } else {
                log.warn("新增片段分类跳过/空结果，metadata 留空，confirm 时兜底。记录ID: {}", recordId);
            }
        } catch (Exception e) {
            log.warn("新增片段分类失败（不阻断），metadata 留空，confirm 时兜底。记录ID: {}，原因: {}",
                    recordId, e.getMessage());
        }

        chunkMapper.insert(chunk);
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
