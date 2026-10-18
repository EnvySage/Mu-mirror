package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录服务接口
 *
 * <p>Record 是用户输入日志，AI 元数据在 Chunk 上。</p>
 * <p>审核时通过 Chunk 接口修改片段；确认授权后将 embedding 写入后台持久任务队列。</p>
 */
public interface RecordService {

    /**
     * 创建记录
     */
    RecordVO create(RecordDTO dto, UUID userId);

    /** 用户对已保存原文明确请求 AI 整理。 */
    RecordVO organize(Long recordId, UUID userId);

    /** 仅记录状态下编辑原文。 */
    RecordVO updateRaw(Long recordId, String content, UUID userId);

    /**
     * 查询记录列表
     */
    List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId);

    /**
     * 根据ID获取记录详情
     */
    RecordVO getById(Long recordId, UUID userId);

    /**
     * 兼容旧客户端的确认入口（未提供授权 ID 时按空集合处理，fail closed）
     */
    RecordVO confirmReview(Long recordId, UUID userId);

    /**
     * 保存片段记忆授权、独立待办授权并应用待办决议
     *
     * <p>body.todoResolutions 处理：confirmed 随入库一起生效（回写源头/证据 chunk taskStatus +
     * registry 状态 + evidence link + 建议 confirmed）；dismissed 静默；未出现在 body 中的
     * 本记录 pending 建议一律 dismissed（含 resolutions=null 的旧客户端行为）。</p>
     *
     * @param resolutions 待办决议列表（可空）
     */
    RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                           List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions);

    RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                           List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                           UUID actionId, Integer actionExecutionVersion);

    /**
     * 软删除记录
     */
    void softDelete(Long recordId, UUID userId);

    /**
     * FAILED 重试：重跑分类管道（设计文档 4.3 / 裁决 #21）
     *
     * <p>仅 FAILED 状态允许；重置为 PROCESSING 并重新发布 RecordCreatedEvent。</p>
     *
     * @return 重试后的记录（状态 PROCESSING）
     */
    RecordVO retry(Long recordId, UUID userId);

    /**
     * 获取指定月份每天的有效记录数
     */
    Map<String, Integer> getCalendarDates(String month, UUID userId);
}
