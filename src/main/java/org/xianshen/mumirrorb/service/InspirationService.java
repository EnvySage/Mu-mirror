package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.InspirationVO;

import java.util.UUID;

/**
 * 写作灵感服务（设计文档 6.8，路线图阶段 5）
 */
public interface InspirationService {

    /**
     * 基于当前草稿生成 2-3 条写作方向（前端输入停顿 &gt;30s 触发）
     *
     * <p>复用 Embed + Chat：草稿 Embed → pgvector 检索相关历史 → Chat 生成提示。
     * 草稿与灵感均不落库。</p>
     */
    InspirationVO inspire(UUID userId, String draft);
}
