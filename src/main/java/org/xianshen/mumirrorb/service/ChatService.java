package org.xianshen.mumirrorb.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;

import java.util.List;
import java.util.UUID;

/**
 * 对话服务（设计文档 6.6，路线图阶段 4）
 */
public interface ChatService {

    /**
     * 对话主流程（SSE 流式推送给前端）
     *
     * <p>流程：ExtractIntent → 四路检索 → 上下文截断 → 流式 Chat 透传 →
     * user/assistant 消息落库（assistant 带 sources）→ 触碰 session.updated_at。
     * 检索为空 / AI 失败按 6.6 兜底文案推送并落库。</p>
     *
     * @param userId   用户 ID
     * @param dto      提问 + 可空 sessionId（空则建新会话）
     * @param emitter  SSE 发射器（Controller 创建）
     */
    void chat(UUID userId, ChatRequestDTO dto, SseEmitter emitter);

    /**
     * 会话列表（updated_at 倒序，裁决 #9）
     */
    List<ChatSessionVO> listSessions(UUID userId);

    /**
     * 会话历史（含全部消息，时间正序；非本人会话报 404 语义）
     */
    ChatSessionVO getSession(UUID sessionId, UUID userId);

    /**
     * 删除会话（消息级联删除，FK ON DELETE CASCADE）
     */
    void deleteSession(UUID sessionId, UUID userId);
}
