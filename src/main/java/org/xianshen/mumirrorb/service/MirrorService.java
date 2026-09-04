package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;

import java.util.UUID;

/**
 * 镜子画像服务（设计文档 6.5，路线图阶段 3）
 */
public interface MirrorService {

    /**
     * 查询最新画像（优先 manual，无则 monthly）
     *
     * @return 最新快照 + 漂移信息；从未生成过画像时返回 VO（id=null）
     */
    MirrorProfileVO getMirror(UUID userId);

    /**
     * 生成 manual 快照（用户点"查看镜子"触发）
     *
     * <p>流程：五维统计 SQL → recent_chats 组装 → gRPC GenerateProfile →
     * 存快照 + 五维拼接 Embed → 分层保留清理（manual 保 2 份）。</p>
     */
    MirrorProfileVO generate(UUID userId);

    /**
     * 漂移检测：指定快照相对上一份 monthly 快照的余弦距离
     *
     * @return 距离（0~2）；无可比对象返回 null
     */
    Double driftDistance(Long snapshotId);
}
