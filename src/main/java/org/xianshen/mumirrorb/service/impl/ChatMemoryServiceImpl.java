package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChatDaySummaryMapper;
import org.xianshen.mumirrorb.mapper.ChatMemoryMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatDaySummary;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Slf4j @Service
public class ChatMemoryServiceImpl implements ChatMemoryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> KINDS = Set.of("fact", "preference", "plan", "event");

    private final ChatDaySummaryMapper summaries;
    private final ChatSessionMapper sessions;
    private final ConversationHistoryMapper history;
    private final ChatMemoryMapper memories;
    private final AiGrpcClient ai;
    private final ActionProposalService actions;
    private final ObjectMapper json;

    public ChatMemoryServiceImpl(ChatDaySummaryMapper summaries, ChatSessionMapper sessions,
            ConversationHistoryMapper history, ChatMemoryMapper memories, AiGrpcClient ai,
            @Lazy ActionProposalService actions, ObjectMapper json) {
        this.summaries = summaries;
        this.sessions = sessions;
        this.history = history;
        this.memories = memories;
        this.ai = ai;
        this.actions = actions;
        this.json = json;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.xianshen.mumirrorb.mapper.ChatSourceExclusionMapper exclusions;
    @org.springframework.beans.factory.annotation.Autowired
    private SnapshotLineageService lineage;

    @Override
    public void prepareEndedDays(UUID userId, LocalDate today) {
        try {
            List<ChatSession> ended = sessions.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getUserId, userId).lt(ChatSession::getDayKey, today)
                    .orderByAsc(ChatSession::getDayKey));
            for (ChatSession session : ended) scheduleSummary(userId, session);
        } catch (Exception e) {
            log.warn("每日聊天总结登记失败，用户: {}, 原因: {}", userId, e.getMessage());
        }
    }

    @Override
    public void retry(UUID userId, UUID sessionId, LocalDate today) {
        ChatSession session = sessions.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId()) || session.getDayKey() == null
                || !session.getDayKey().isBefore(today)) throw new BusinessException(4041, "已结束聊天日期不存在");
        scheduleSummary(userId, session);
    }

    void process(UUID userId, ChatSession session) {
        SummaryWork work = claimSummary(userId, session);
        if (work != null) summarize(work);
    }

    private void scheduleSummary(UUID userId, ChatSession session) {
        SummaryWork work = claimSummary(userId, session);
        if (work != null) java.util.concurrent.CompletableFuture.runAsync(() -> summarize(work));
    }

    private SummaryWork claimSummary(UUID userId, ChatSession session) {
        List<ConversationHistory> messages = history.selectUserMessages(session.getId(), userId);
        // Empty days never get a summary row or model call.
        if (messages.isEmpty()) return null;
        LocalDate day = session.getDayKey();
        String hash = sourceHash(messages);
        ChatDaySummary existing = summaries.find(userId, day);
        if (existing != null && hash.equals(existing.getSourceHash()) && "READY".equals(existing.getState())) {
            ensureCandidateActions(userId, session, day, hash, messages, null);
            return null;
        }
        if (existing == null) {
            if (summaries.insertLease(userId, day, session.getId(), hash) == 0) return null;
        } else if (hash.equals(existing.getSourceHash())) {
            if (summaries.acquireRetry(userId, day, hash) == 0) return null;
        } else if (summaries.refreshSource(userId, day, session.getId(), hash) == 0) return null;
        ChatDaySummary lease = summaries.find(userId, day);
        if (lease == null || !hash.equals(lease.getSourceHash()) || !"PROCESSING".equals(lease.getState())) return null;
        long version = lease.getVersion();
        return new SummaryWork(userId, session, day, hash, version, messages);
    }

    private void summarize(SummaryWork work) {
        UUID userId = work.userId();
        ChatSession session = work.session();
        LocalDate day = work.day();
        String hash = work.hash();
        long version = work.version();
        List<ConversationHistory> messages = work.messages();
        try {
            List<MirrorChatProto.ChatDayMessage> input = messages.stream().map(m -> MirrorChatProto.ChatDayMessage.newBuilder()
                    .setMessageId(m.getId()).setContent(m.getContent())
                    .setCreatedAt(m.getCreatedAt().atZoneSameInstant(ZONE).toString()).build()).toList();
            MirrorChatProto.SummarizeDayResponse response = ai.summarizeDay(userId, day, hash, input, "Asia/Shanghai");
            if (!hash.equals(response.getSourceHash())) throw new IllegalStateException("AI summary source hash mismatch");
            if (response.getMemoryCandidatesCount() > 3) throw new IllegalStateException("Too many memory candidates");
            Set<Long> allowed = new HashSet<>();
            messages.forEach(m -> allowed.add(m.getId()));
            List<JsonNode> candidates = new ArrayList<>();
            for (MirrorChatProto.MemoryCandidate candidate : response.getMemoryCandidatesList()) {
                JsonNode draft = normalizeCandidate(userId, session, day, hash, candidate, allowed);
                if (draft != null) candidates.add(draft);
            }
            ensureCandidateActions(userId, session, day, hash, messages, candidates);
            // Store only after candidates are durably prepared; retries reuse their prepare keys.
            summaries.complete(userId, day, hash, version, response.getSummary().trim());
        } catch (Exception e) {
            summaries.fail(userId, day, hash, version, errorCode(e));
            log.warn("每日聊天总结失败，可重试，用户: {}, 日期: {}, 原因: {}", userId, day, e.getMessage());
        }
    }

    private record SummaryWork(UUID userId, ChatSession session, LocalDate day, String hash,
            long version, List<ConversationHistory> messages) { }

    private JsonNode normalizeCandidate(UUID userId, ChatSession session, LocalDate day, String hash,
            MirrorChatProto.MemoryCandidate c, Set<Long> allowed) {
        String content = c.getContent().trim();
        String kind = c.getKind().trim().toLowerCase(Locale.ROOT);
        if (content.isBlank() || content.length() > 500 || !KINDS.contains(kind)
                || c.getSourceMessageIdsCount() == 0) return null;
        List<Long> ids = c.getSourceMessageIdsList();
        if (ids.stream().anyMatch(id -> id <= 0 || !allowed.contains(id)) || ids.size() != new HashSet<>(ids).size()) return null;
        ObjectNode draft = json.createObjectNode();
        String seed = userId + ":" + day + ":" + hash + ":" + c.toString();
        UUID memoryId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        draft.put("memoryId", memoryId.toString()).put("content", content).put("kind", kind).put("sourceDate", day.toString()).put("version", 1);
        var sourceIds = draft.putArray("sourceMessageIds"); ids.forEach(sourceIds::add);
        if (c.hasEventDate() && !c.getEventDate().isBlank()) {
            try { draft.put("eventDate", LocalDate.parse(c.getEventDate()).toString()); }
            catch (Exception ignored) { return null; }
        }
        if (c.hasExpiresAt() && !c.getExpiresAt().isBlank()) {
            try { draft.put("expiresAt", expiry(c.getExpiresAt()).toString()); }
            catch (Exception ignored) { return null; }
        } else if ("event".equals(kind)) {
            draft.put("expiresAt", day.plusDays(30).atStartOfDay(ZONE).toOffsetDateTime().toString());
        }
        return draft;
    }

    private void ensureCandidateActions(UUID userId, ChatSession session, LocalDate day, String hash,
            List<ConversationHistory> messages, List<JsonNode> freshCandidates) {
        // Candidate JSON is not stored in summaries; existing proposals are the durable candidate store.
        // New candidates are prepared once; the prepare key makes retries and concurrent calls idempotent.
        if (freshCandidates == null || freshCandidates.isEmpty()) return;
        // A summary can finish after a source was forgotten. Re-read the filtered source set
        // immediately before creating cards; the original work snapshot is not authorization.
        Set<Long> currentlyAllowed = new HashSet<>();
        history.selectUserMessages(session.getId(), userId).forEach(m -> currentlyAllowed.add(m.getId()));
        int ordinal = 0;
        for (JsonNode draft : freshCandidates) {
            List<Long> sourceIds = new ArrayList<>();
            draft.path("sourceMessageIds").forEach(node -> sourceIds.add(node.asLong(-1)));
            if (sourceIds.isEmpty() || sourceIds.stream().anyMatch(id -> !currentlyAllowed.contains(id))) {
                ordinal++;
                continue;
            }
            long sourceId = sourceIds.get(0);
            ActionPrepareDTO body = new ActionPrepareDTO();
            body.setKind("memory.accept"); body.setSourceSessionId(session.getId()); body.setSourceMessageId(sourceId);
            body.setPrepareKey("memory-day:" + session.getId() + ":" + hash + ":" + ordinal++);
            ObjectNode payload = json.createObjectNode(); payload.set("memoryDraft", draft.deepCopy()); body.setPayload(payload);
            actions.prepare(userId, body);
        }
    }

    @Override
    public UUID validateDraft(UUID userId, JsonNode draft) {
        if (draft == null || !draft.isObject()) throw new BusinessException(400, "候选内容无效");
        Set<String> allowedFields = Set.of("memoryId", "content", "kind", "sourceDate", "sourceMessageIds", "eventDate", "expiresAt", "version");
        draft.fieldNames().forEachRemaining(field -> { if (!allowedFields.contains(field)) throw new BusinessException(400, "候选包含未知字段"); });
        UUID memoryId;
        try { memoryId = UUID.fromString(draft.path("memoryId").asText()); }
        catch (Exception e) { throw new BusinessException(400, "记忆候选 ID 无效"); }
        String content = draft.path("content").asText("").trim();
        String kind = draft.path("kind").asText("").toLowerCase(Locale.ROOT);
        LocalDate day;
        try { day = LocalDate.parse(draft.path("sourceDate").asText()); }
        catch (Exception e) { throw new BusinessException(400, "记忆来源日期无效"); }
        JsonNode idsNode = draft.path("sourceMessageIds");
        if (content.isBlank() || content.length() > 500 || !KINDS.contains(kind) || !idsNode.isArray()
                || idsNode.isEmpty() || idsNode.size() > 10 || draft.path("version").asInt() < 1)
            throw new BusinessException(400, "记忆候选字段无效");
        List<Long> ids = new ArrayList<>();
        idsNode.forEach(id -> ids.add(id.asLong(-1)));
        if (ids.stream().anyMatch(id -> id <= 0) || ids.size() != new HashSet<>(ids).size())
            throw new BusinessException(400, "记忆来源消息 ID 无效");
        ChatSession session = sessions.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId).eq(ChatSession::getDayKey, day));
        if (session == null || memories.countOwnedUserSources(userId, session.getId(), ids) != ids.size())
            throw new BusinessException(400, "记忆来源不属于对应聊天日期");
        try {
            if (draft.hasNonNull("eventDate")) LocalDate.parse(draft.path("eventDate").asText());
            if (draft.hasNonNull("expiresAt")) expiry(draft.path("expiresAt").asText());
        } catch (Exception e) { throw new BusinessException(400, "记忆候选日期格式无效"); }
        return session.getId();
    }

    @Override public ChatDaySummary summaryForSession(UUID userId, UUID sessionId) {
        ChatSession session = sessions.selectById(sessionId);
        return session == null || !userId.equals(session.getUserId()) || session.getDayKey() == null
                ? null : summaries.find(userId, session.getDayKey());
    }

    @Override public List<ActionProposalVO> candidateActions(UUID userId, UUID sessionId) {
        return actions.listForSession(userId, sessionId).stream().filter(a -> "memory.accept".equals(a.getKind())).toList();
    }

    @Override public List<org.xianshen.mumirrorb.pojo.VO.TodayChatVO.EndedDaySummaryVO> endedSummaries(UUID userId, LocalDate today) {
        return summaries.listForUser(userId).stream().filter(row -> row.getSourceDate().isBefore(today))
                .map(row -> org.xianshen.mumirrorb.pojo.VO.TodayChatVO.EndedDaySummaryVO.builder()
                        .sourceDate(row.getSourceDate()).sourceSessionId(row.getSourceSessionId())
                        .summary(row.getSummary()).sourceHash(row.getSourceHash()).state(row.getState())
                        .actions(candidateActions(userId, row.getSourceSessionId())).build()).toList();
    }

    @Override
    public ActionProposalVO prepareCandidate(UUID userId, UUID sessionId, Long sourceMessageId,
            String prepareKey, Map<String, Object> fields) {
        if (fields == null || !Set.of("content", "kind", "eventDate", "expiresAt").containsAll(fields.keySet()))
            throw new BusinessException(400, "记忆候选含未知字段");
        ChatSession session = sessions.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId()) || session.getDayKey() == null)
            throw new BusinessException(400, "记忆候选必须来自每日会话");
        boolean sourceExists = history.selectUserMessages(sessionId, userId).stream()
                .anyMatch(message -> Objects.equals(message.getId(), sourceMessageId));
        if (!sourceExists) throw new BusinessException(400, "记忆候选来源必须是本轮用户消息");
        String content = Objects.toString(fields.get("content"), "").trim();
        String kind = Objects.toString(fields.get("kind"), "").trim().toLowerCase(Locale.ROOT);
        if (content.isBlank() || content.length() > 500 || !KINDS.contains(kind))
            throw new BusinessException(400, "记忆候选内容或类型无效");
        ObjectNode draft = json.createObjectNode().put("memoryId", UUID.randomUUID().toString())
                .put("content", content).put("kind", kind).put("sourceDate", session.getDayKey().toString()).put("version", 1);
        draft.putArray("sourceMessageIds").add(sourceMessageId);
        if (fields.get("eventDate") != null) draft.put("eventDate", LocalDate.parse(fields.get("eventDate").toString()).toString());
        if (fields.get("expiresAt") != null) draft.put("expiresAt", expiry(fields.get("expiresAt").toString()).toString());
        else if ("event".equals(kind))
            draft.put("expiresAt", session.getDayKey().plusDays(30).atStartOfDay(ZONE).toOffsetDateTime().toString());
        validateDraft(userId, draft);
        ObjectNode payload = json.createObjectNode(); payload.set("memoryDraft", draft);
        ActionPrepareDTO body = new ActionPrepareDTO("memory.accept", payload, prepareKey, sessionId, sourceMessageId);
        return actions.prepare(userId, body);
    }

    private static String sourceHash(List<ConversationHistory> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ConversationHistory m : messages) {
                digest.update((m.getId() + "\u0000" + m.getCreatedAt() + "\u0000" + m.getContent() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String errorCode(Exception e) { return e instanceof BusinessException ? "AI_NOT_CONFIGURED_OR_REJECTED" : "SUMMARY_FAILED"; }

    @Override public List<org.xianshen.mumirrorb.pojo.DO.ChatMemory> listActive(UUID userId) {
        return memories.listActive(userId);
    }

    @Override public org.xianshen.mumirrorb.pojo.DO.ChatMemory getActive(UUID userId, UUID memoryId) {
        var memory = memories.findOwned(userId, memoryId);
        if (memory == null || !"ACTIVE".equals(memory.getState())) throw new BusinessException(404, "聊天记忆不存在");
        return memory;
    }

    @Override @Transactional
    public void forget(UUID userId, UUID memoryId, long expectedVersion) {
        var memory = memories.lockOwned(userId, memoryId);
        if (memory == null || !"ACTIVE".equals(memory.getState())) throw new BusinessException(404, "聊天记忆不存在或已遗忘");
        if (memory.getVersion() != expectedVersion) throw new BusinessException(409, "记忆版本已变化，请刷新后重试");
        List<Long> sourceIds = parseSourceIds(memory.getSourceMessageIds());
        if (sourceIds.isEmpty()) throw new BusinessException(409, "记忆来源已失效，无法安全遗忘");
        List<ConversationHistory> sessionMessages = history.selectBySession(memory.getSourceSessionId(), userId);
        Set<Long> userSources = new HashSet<>(sourceIds);
        Set<Long> excludedIds = new LinkedHashSet<>();
        if (lineage != null) lineage.lockUserLineage(userId);
        boolean excludedTurn = false;
        for (ConversationHistory message : sessionMessages) {
            if ("user".equals(message.getRole())) excludedTurn = userSources.contains(message.getId());
            if (excludedTurn) excludedIds.add(message.getId());
        }
        if (!excludedIds.containsAll(userSources)) throw new BusinessException(409, "记忆来源不完整，无法安全遗忘");
        List<Long> dependentReplies = history.selectAssistantRepliesDependingOn(userId, sourceIds.toArray(Long[]::new));
        if (dependentReplies != null) excludedIds.addAll(dependentReplies);

        List<org.xianshen.mumirrorb.pojo.DO.ChatSourceExclusion> tombstones = excludedIds.stream().map(id ->
                org.xianshen.mumirrorb.pojo.DO.ChatSourceExclusion.builder().userId(userId)
                        .sourceKind("chat_message").sourceId(id).reason("memory_forgotten").build()).toList();
        if (exclusions.insertAll(tombstones) < tombstones.size()) {
            Set<Long> alreadyExcluded = new HashSet<>(exclusions.listSourceIds(userId, "chat_message"));
            if (!alreadyExcluded.containsAll(excludedIds)) throw new IllegalStateException("source exclusion write incomplete");
        }
        if (memories.forgetOwned(userId, memoryId, expectedVersion) != 1)
            throw new BusinessException(409, "记忆已变化，请刷新后重试");
        summaries.invalidateForForgottenSource(userId, memory.getSourceDate());
        cancelRelatedPendingActions(userId, memory, userSources);
        if (lineage != null) lineage.invalidateForForgottenChatSources(userId, excludedIds.stream().sorted().toList());
    }

    @Override @Transactional
    public void invalidateEditedMemorySources(UUID userId, UUID memoryId, long expectedVersion) {
        var memory = memories.findOwned(userId, memoryId);
        if (memory == null || !"ACTIVE".equals(memory.getState()) || memory.getVersion() != expectedVersion)
            throw new BusinessException(409, "记忆已变化或已遗忘，请刷新后重试");
        List<Long> ids = parseSourceIds(memory.getSourceMessageIds());
        if (ids.isEmpty()) throw new BusinessException(409, "记忆来源已失效，无法更新画像谱系");
        if (lineage != null) lineage.invalidateForChangedChatSources(userId, ids);
    }

    @Override @Transactional
    public Long persistAssistantReply(ConversationHistory message) {
        if (lineage != null) lineage.lockUserLineage(message.getUserId());
        history.insert(message);
        return message.getId();
    }

    private void cancelRelatedPendingActions(UUID userId, org.xianshen.mumirrorb.pojo.DO.ChatMemory memory,
                                              Set<Long> sourceIds) {
        for (ActionProposalVO proposal : actions.listForSession(userId, memory.getSourceSessionId())) {
            if (!"PENDING".equals(proposal.getState())) continue;
            boolean relatedSource = proposal.getSourceMessageId() != null && sourceIds.contains(proposal.getSourceMessageId());
            boolean relatedMemory = proposal.getPayload() != null
                    && memory.getId().toString().equals(proposal.getPayload().path("memoryId").asText());
            boolean relatedDraftSource = proposal.getPayload() != null
                    && containsAnySource(proposal.getPayload().path("memoryDraft").path("sourceMessageIds"), sourceIds);
            if (!relatedSource && !relatedMemory && !relatedDraftSource) continue;
            ActionProposalVO cancelled = actions.cancel(userId, proposal.getId(), proposal.getVersion());
            if (cancelled == null || !"CANCELLED".equals(cancelled.getState()))
                throw new BusinessException(409, "关联待确认操作未能取消，无法遗忘该记忆");
        }
    }

    private static boolean containsAnySource(JsonNode ids, Set<Long> sourceIds) {
        if (ids == null || !ids.isArray()) return false;
        for (JsonNode id : ids) if (sourceIds.contains(id.asLong(-1))) return true;
        return false;
    }

    private static List<Long> parseSourceIds(String sourceMessageIds) {
        try { return new ObjectMapper().readValue(sourceMessageIds, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    private static OffsetDateTime expiry(String value) {
        try { return OffsetDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) {
            return LocalDate.parse(value).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        }
    }

}
