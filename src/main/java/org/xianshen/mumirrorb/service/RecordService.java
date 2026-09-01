package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录服务接口
 *
 * <p>Record 是用户输入日志，AI 元数据在 Chunk 上。</p>
 * <p>审核时通过 Chunk 接口修改元数据，确认后对 Chunk 做 embedding。</p>
 */
public interface RecordService {

    /**
     * 创建记录
     */
    RecordVO create(RecordDTO dto, UUID userId);

    /**
     * 查询记录列表
     */
    List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId);

    /**
     * 根据ID获取记录详情
     */
    RecordVO getById(Long recordId, UUID userId);

    /**
     * 确认审查完成（遍历 Chunk 做 embedding）
     */
    RecordVO confirmReview(Long recordId, UUID userId);

    /**
     * 软删除记录
     */
    void softDelete(Long recordId, UUID userId);

    /**
     * 获取指定月份每天的有效记录数
     */
    Map<String, Integer> getCalendarDates(String month, UUID userId);
}
