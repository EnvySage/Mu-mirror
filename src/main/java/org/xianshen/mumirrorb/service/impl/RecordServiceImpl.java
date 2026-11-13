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

/**
 * 记录服务实现
 */

@Service
@RequiredArgsConstructor
public class RecordServiceImpl implements RecordService {
    protected static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RecordServiceImpl.class);
    private final RecordCommands recordCommands = new RecordCommands(this);
    private final RecordQueries recordQueries = new RecordQueries(this);
    private final RecordReview recordReview = new RecordReview(this);


    @org.springframework.beans.factory.annotation.Autowired protected org.xianshen.mumirrorb.mapper.GrowthEntryMapper growthEntries;
    @org.springframework.beans.factory.annotation.Autowired protected org.xianshen.mumirrorb.service.GrowthService growthService;

    protected final RecordMapper recordMapper;
    protected final ChunkMapper chunkMapper;
    protected final SnapshotLineageService snapshotLineageService;
    protected final ApplicationEventPublisher eventPublisher;
    protected final AiGrpcClient aiGrpcClient;
    protected final org.springframework.context.ApplicationContext applicationContext;
    protected final ChunkEmbeddingJobService chunkEmbeddingJobService;


    protected org.xianshen.mumirrorb.service.TodoRegistryService todoRegistryService() {
        return recordCommands.todoRegistryService();
    }

    @Override
    @Transactional
    public RecordVO create(RecordDTO dto, UUID userId) {
        return recordCommands.create(dto, userId);
    }

    @Override
    @Transactional
    public RecordVO organize(Long recordId, UUID userId) {
        return recordCommands.organize(recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO updateRaw(Long recordId, String content, UUID userId) {
        return recordCommands.updateRaw(recordId, content, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId) {
        return recordQueries.list(queryDTO, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public RecordVO getById(Long recordId, UUID userId) {
        return recordQueries.getById(recordId, userId);
    }

    @Override
    @Transactional
    public void softDelete(Long recordId, UUID userId) {
        recordCommands.softDelete(recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO retry(Long recordId, UUID userId) {
        return recordCommands.retry(recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId) {
        return recordReview.confirmReview(recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions) {
        return recordReview.confirmReview(recordId, userId, aiMemoryChunkIds, todoChunkIds, resolutions);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion) {
        return recordReview.confirmReview(recordId, userId, aiMemoryChunkIds, todoChunkIds, resolutions, actionId, actionExecutionVersion);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion,
                                  List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO> growthAdjustments, Long axesVersion) {
        return recordReview.confirmReview(recordId, userId, aiMemoryChunkIds, todoChunkIds, resolutions, actionId, actionExecutionVersion, growthAdjustments, axesVersion);
    }



    protected Object tagsOf(Map<String, Object> metadata) {
        return recordQueries.tagsOf(metadata);
    }


    protected List<Object> growthSuggestionsOf(Record record) {
        return recordQueries.growthSuggestionsOf(record);
    }

    protected RecordVO toVO(Record record) {
        return recordQueries.toVO(record);
    }

    /** 只透出服务器持久化的版本；旧内存对象未赋值时按兼容策略解释。 */
    protected int indexPolicyVersionOf(Record record) {
        return record.getIndexPolicyVersion() == null ? 0 : record.getIndexPolicyVersion();
    }


    protected ChunkVO toChunkVO(Chunk chunk) {
        return recordQueries.toChunkVO(chunk);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> getCalendarDates(String month, UUID userId) {
        return recordQueries.getCalendarDates(month, userId);
    }
}
