package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DO.ChatDaySummary;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import java.time.LocalDate;
import java.util.List;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

public interface ChatMemoryService {
    void prepareEndedDays(UUID userId, LocalDate today);
    void retry(UUID userId, UUID sessionId, LocalDate today);
    ChatDaySummary summaryForSession(UUID userId, UUID sessionId);
    List<ActionProposalVO> candidateActions(UUID userId, UUID sessionId);
    UUID validateDraft(UUID userId, JsonNode memoryDraft);
    List<org.xianshen.mumirrorb.pojo.VO.TodayChatVO.EndedDaySummaryVO> endedSummaries(UUID userId, LocalDate today);
    org.xianshen.mumirrorb.pojo.VO.ActionProposalVO prepareCandidate(UUID userId, UUID sessionId, Long sourceMessageId,
            String prepareKey, java.util.Map<String, Object> candidateFields);
    List<ChatMemory> listActive(UUID userId);
    ChatMemory getActive(UUID userId, UUID memoryId);
    void forget(UUID userId, UUID memoryId, long expectedVersion);
    void invalidateEditedMemorySources(UUID userId, UUID memoryId, long expectedVersion);
    Long persistAssistantReply(org.xianshen.mumirrorb.pojo.DO.ConversationHistory message);
}
