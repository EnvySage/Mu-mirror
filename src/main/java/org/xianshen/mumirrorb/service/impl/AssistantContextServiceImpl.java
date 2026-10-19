package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.mapper.ChatMemoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ActionStatusSummary;
import org.xianshen.mumirrorb.service.AssistantContextService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

@Service @RequiredArgsConstructor
public class AssistantContextServiceImpl implements AssistantContextService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TERMS = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9]{2,}");
    private static final int MAX_MEMORIES = 5;

    private final ChatMemoryMapper memories;
    private final ActionProposalService actions;

    @Override public CommonProto.AssistantContext build(UUID userId, UUID sessionId, String question) {
        return buildInternal(userId, sessionId, question, false).context();
    }

    @Override public BuiltContext buildForProfile(UUID userId, String question) {
        return buildInternal(userId, null, question, true);
    }

    private BuiltContext buildInternal(UUID userId, UUID sessionId, String question, boolean profile) {
        var builder = CommonProto.AssistantContext.newBuilder()
                .setRoleTone("温和、直接、基于用户提供的信息；未确认操作只说明状态")
                .setCurrentDate(LocalDate.now(ZONE).toString()).setTimezone(ZONE.getId());
        if (sessionId != null) {
            builder.setActionStatusSummary(ActionStatusSummary.format(actions.listForSession(userId, sessionId)));
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        List<ChatMemory> available = memories.selectAuthorizedForContext(userId, now);
        if (available == null || available.isEmpty()) return new BuiltContext(builder.build(), List.of());
        Set<String> terms = terms(question);
        List<ChatMemory> selected = available.stream()
                .filter(m -> m != null && "ACTIVE".equals(m.getState()))
                .filter(m -> m.getExpiresAt() == null || m.getExpiresAt().isAfter(now))
                .filter(m -> profile || relevance(m.getContent(), terms) > 0)
                .sorted(Comparator.<ChatMemory>comparingInt(m -> relevance(m.getContent(), terms)).reversed()
                        .thenComparing(ChatMemory::getSourceDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ChatMemory::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_MEMORIES).toList();
        List<Long> sourceIds = new ArrayList<>();
        for (ChatMemory memory : selected) {
            List<Long> sourceMessageIdsForMemory = sourceMessageIds(memory.getSourceMessageIds());
            var item = CommonProto.ChatMemoryContext.newBuilder().setMemoryId(memory.getId().toString())
                    .setContent(memory.getContent()).setKind(memory.getKind())
                    .setSourceDate(memory.getSourceDate() == null ? "" : memory.getSourceDate().toString())
                    .addAllSourceMessageIds(sourceMessageIdsForMemory);
            if (memory.getEventDate() != null) item.setEventDate(memory.getEventDate().toString());
            if (memory.getExpiresAt() != null) item.setExpiresAt(memory.getExpiresAt().toString());
            builder.addMemories(item);
            sourceIds.addAll(sourceMessageIdsForMemory);
        }
        return new BuiltContext(builder.build(), sourceIds.stream().distinct().sorted().toList());
    }

    private static Set<String> terms(String question) {
        if (question == null || question.isBlank()) return Set.of();
        Set<String> terms = new LinkedHashSet<>();
        var matcher = TERMS.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) terms.add(matcher.group());
        // Chinese free text: include bigrams so short topical phrases can match atomized memories.
        String han = question.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i + 1 < han.length(); i++) terms.add(han.substring(i, i + 2));
        return terms;
    }

    private static int relevance(String content, Set<String> terms) {
        if (content == null || content.isBlank() || terms.isEmpty()) return 0;
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (normalized.contains(term)) score += term.length() > 2 ? 2 : 1;
        return score;
    }

    private static List<Long> sourceMessageIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() { });
        } catch (Exception ignored) { return List.of(); }
    }
}
