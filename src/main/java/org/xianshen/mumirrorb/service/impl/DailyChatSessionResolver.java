package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** Resolves daily sessions by the server's date; legacy sessions keep their nullable day key. */
@Component @RequiredArgsConstructor
public class DailyChatSessionResolver {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ChatSessionMapper sessions;

    public ChatSession resolveSend(UUID userId, UUID requestedSessionId, String title, LocalDate today) {
        if (requestedSessionId != null) {
            ChatSession existing = sessions.selectById(requestedSessionId);
            if (existing == null || !userId.equals(existing.getUserId()))
                throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "会话不存在");
            // Legacy sessions remain reviewable, but any new send resolves to today's daily session.
            if (existing.getDayKey() != null && !existing.getDayKey().isBefore(today)) return existing;
        }
        return getOrCreate(userId, today, title);
    }

    public ChatSession getOrCreate(UUID userId, LocalDate day, String title) {
        ChatSession found = sessions.findDaily(userId, day);
        if (found != null) return found;
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        ChatSession proposed = ChatSession.builder().id(UUID.randomUUID()).userId(userId)
                .title(title).dayKey(day).timezone(ZONE.getId()).createdAt(now).updatedAt(now).build();
        sessions.insertDaily(proposed);
        ChatSession winner = sessions.findDaily(userId, day);
        if (winner == null) throw new IllegalStateException("Daily session insert did not resolve");
        return winner;
    }
}
