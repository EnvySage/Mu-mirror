package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.ExportVO;

import java.util.UUID;

/**
 * 数据导出服务（设计文档 6.9，裁决 #19：只导出不导入）
 */
public interface ExportService {

    /**
     * 全量结构化导出（JSON）
     *
     * <p>自动排除所有 embedding 向量字段；全量同步导出不分页（年数据量 1000-2000 条）。</p>
     */
    ExportVO exportJson(UUID userId);
}
