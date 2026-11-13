package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.CalendarDayVO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.pipeline.ClassifyItemConverter;
import org.xianshen.mumirrorb.pipeline.TimeSubstitutionApplier;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;

import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

import static org.xianshen.mumirrorb.service.impl.RecordServiceImpl.*;

/** RecordQueries keeps the service entry points and transaction boundaries in RecordServiceImpl. */
final class RecordQueries {
    private final RecordServiceImpl owner;
    RecordQueries(RecordServiceImpl owner) { this.owner=owner; }

    @Transactional(readOnly = true)
    public List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId) {
        LocalDate startDate = queryDTO.getStartDate();
        LocalDate endDate = queryDTO.getEndDate();

        if (startDate == null && endDate == null) {
            startDate = LocalDate.now();
            endDate = startDate;
        } else if (startDate == null) {
            startDate = endDate;
        } else if (endDate == null) {
            endDate = startDate;
        }

        // 日期口径统一 Asia/Shanghai（与日历统计 TO_CHAR ... AT TIME ZONE 一致）
        OffsetDateTime startDateTime = startDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime endDateTime = endDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();

        LambdaQueryWrapper<Record> wrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")       // 统计口径：只查用户输入，排除系统记录
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, startDateTime)
                .lt(Record::getCreatedAt, endDateTime)
                .orderByDesc(Record::getCreatedAt);

        if (queryDTO.getMood() != null && !queryDTO.getMood().isBlank()) {
            wrapper.apply("""
                EXISTS (SELECT 1 FROM chunks c WHERE c.record_id=records.id AND c.user_id={0}::uuid
                  AND (c.metadata->>'mood'={1} OR c.metadata->'mood' @> to_jsonb(ARRAY[{1}]::text[])))
                """, userId, queryDTO.getMood().trim());
        }
        if (queryDTO.getContentType() != null && !queryDTO.getContentType().isBlank()) {
            wrapper.apply("""
                EXISTS (SELECT 1 FROM chunks c WHERE c.record_id=records.id AND c.user_id={0}::uuid
                  AND c.metadata->>'contentType'={1})
                """, userId, queryDTO.getContentType().trim());
        }
        List<Record> records = owner.recordMapper.selectList(wrapper);
        if (!records.isEmpty() && queryDTO.getTags() != null && !queryDTO.getTags().isEmpty()) {
            Set<String> requested = queryDTO.getTags().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet());
            if (!requested.isEmpty()) {
                Set<Long> matching = owner.chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                                .eq(Chunk::getUserId, userId).in(Chunk::getRecordId, records.stream().map(Record::getId).toList()))
                        .stream().filter(c -> c.getMetadata() != null && owner.tagsOf(c.getMetadata()) instanceof List<?> tags
                                && tags.stream().map(String::valueOf).anyMatch(requested::contains))
                        .map(Chunk::getRecordId).collect(java.util.stream.Collectors.toSet());
                records = records.stream().filter(r -> matching.contains(r.getId())).toList();
            }
        }
        owner.log.info("查询到 {} 条记录，用户: {}, 日期范围: {} ~ {}", records.size(), userId, startDate, endDate);

        return records.stream()
                .map(owner::toVO)
                .toList();
    }


    @Transactional(readOnly = true)
    public RecordVO getById(Long recordId, UUID userId) {
        Record record = owner.recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );

        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }

        return owner.toVO(record);
    }



    /**
     * DO → VO 转换（包含关联的 Chunk 列表）
     */
    /** 自由标签统一键：新数据 userTags，兼容旧 user_tags / tags。 */
    protected Object tagsOf(Map<String, Object> metadata) {
        Object tags = metadata.get("userTags");
        if (tags instanceof List) return tags;
        tags = metadata.get("user_tags");
        if (tags instanceof List) return tags;
        return metadata.get("tags");
    }



    /** 解析记录上的五维成长建议；历史坏数据按空处理，不影响记录详情。 */
    protected List<Object> growthSuggestionsOf(Record record) {
        String raw = record.getGrowthSuggestions();
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        } catch (Exception e) {
            owner.log.warn("记录成长建议解析失败，按空处理，recordId={}: {}", record.getId(), e.getMessage());
            return List.of();
        }
    }



    protected RecordVO toVO(Record record) {
        // 查询关联的 Chunks
        List<Chunk> chunks = owner.chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getRecordId, record.getId())
                        .eq(Chunk::getUserId, record.getUserId())
                        .orderByAsc(Chunk::getId)
        );

        List<ChunkVO> chunkVOs = chunks.stream()
                .map(owner::toChunkVO)
                .toList();

        return RecordVO.builder()
                .id(record.getId())
                .content(record.getContent())
                .segments(chunkVOs.stream().map(ChunkVO::getSegment).toList())
                .status(record.getStatus())
                .aiProcessingAuthorized(record.getAiProcessingAuthorized())
                .memoryState(record.getMemoryState() == null ? RecordMemoryState.NONE : record.getMemoryState())
                .processingVersion(record.getProcessingVersion())
                .indexPolicyVersion(owner.indexPolicyVersionOf(record))
                .correctionOfRecordId(record.getCorrectionOfRecordId())
                .memoryFailureReason(record.getMemoryFailureReason())
                .queuedEmbeddingJobIds(record.getQueuedEmbeddingJobIds())
                .growthSuggestions(owner.growthSuggestionsOf(record))
                .growthAxesVersion(record.getGrowthAxesVersion())
                .userReviewed(record.getUserReviewed())
                .failReason(record.getFailReason())
                .chunks(chunkVOs)
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }



    /**
     * Chunk DO → ChunkVO 转换
     */
    protected ChunkVO toChunkVO(Chunk chunk) {
        return ChunkVO.builder()
                .id(chunk.getId())
                .recordId(chunk.getRecordId())
                .segment(chunk.getSegment())
                .metadata(chunk.getMetadata())
                .hasEmbedding(chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty())
                .aiMemoryApproved(Boolean.TRUE.equals(chunk.getAiMemoryApproved()))
                .embeddingEnabled(chunk.getEmbeddingEnabled())
                .embeddingReason(chunk.getMetadata() != null
                        && chunk.getMetadata().get("embeddingReason") instanceof String reason ? reason : null)
                .todoApproved(Boolean.TRUE.equals(chunk.getTodoApproved()))
                .contentVersion(chunk.getContentVersion())
                .embeddingStatus(chunk.getEmbeddingStatus() == null
                        ? (chunk.getEmbedding() == null ? "not_requested" : "ready")
                        : chunk.getEmbeddingStatus())
                .embeddingError(chunk.getEmbeddingError())
                .build();
    }


    @Transactional(readOnly = true)
    public Map<String, Integer> getCalendarDates(String month, UUID userId) {
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.plusMonths(1).atDay(1);

        OffsetDateTime monthStart = startDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();
        OffsetDateTime monthEnd = endDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();

        List<CalendarDayVO> rows = owner.recordMapper.countByDay(userId, monthStart, monthEnd);

        Map<String, Integer> result = new HashMap<>();
        for (CalendarDayVO row : rows) {
            result.put(row.getDate(), row.getCnt());
        }
        return result;
    }
}
