package org.xianshen.mumirrorb.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.agent.ChatFinalResult;
import org.xianshen.mumirrorb.agent.ChatInvocation;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;

import java.util.List;
import java.util.UUID;

/**
 * 对话服务（设计文档 6.6，路线图阶段 4）
 */
public interface ChatService {

    /** Runs the established legacy question and retrieval path after shared request acceptance. */
    ChatFinalResult runLegacy(ChatInvocation invocation, SseEmitter emitter);

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
