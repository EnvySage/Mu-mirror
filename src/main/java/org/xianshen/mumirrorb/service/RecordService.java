package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;

/**
 * 记录服务接口
 */
public interface RecordService {

    /**
     * 创建记录（提交 → 清洗 → AI 处理 → 保存）
     *
     * @param dto    记录数据
     * @param userId 当前用户ID
     * @return 记录视图
     */
    RecordVO create(RecordDTO dto, String userId);
}
