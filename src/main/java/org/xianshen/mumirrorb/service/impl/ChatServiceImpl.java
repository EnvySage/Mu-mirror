package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;
import org.xianshen.mumirrorb.service.ChatService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话服务实现（设计文档 6.6）
 *
 * <p>流程：ExtractIntent（query_type 四选一）→ 四路检索（PROFILE 快照 / STRUCTURED SQL /
 * SEMANTIC pgvector / HYBRID 预过滤+向量+时间衰减）→ 上下文截断（快照 ≤2、日记 ≤5、
 * 历史最近 3 轮）→ 流式 Chat 透传（SSE）→ 消息落库（assistant 带 sources）→
 * 触碰 session.updated_at。</p>
 *
 * <p>时间衰减：final_score = (embedding &lt;=&gt; query) × 1/(1 + 天数差/half_life)，
 * half_life 读 user_settings.rag_half_life；ExtractIntent 返回 time_range 时关衰减。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CONTEXT_CHUNK_LIMIT = 5;      // 日记 ≤5 条
    private static final int SNAPSHOT_LIMIT = 2;           // 快照 ≤2 份
    private static final int HISTORY_ROUNDS = 3;           // 对话历史最近 3 轮
    private static final int TITLE_MAX_LEN = 50;
    private static final int QUOTE_MAX_LEN = 60;
    private static final double DEFAULT_HALF_LIFE = 30.0;
    private static final String FALLBACK_NO_ANSWER = "暂时无法回答";
    private static final String FALLBACK_NO_RECORDS = "没有找到相关记录";

    private final ChatSessionMapper sessionMapper;
    private final ConversationHistoryMapper historyMapper;
    private final ChatSearchMapper searchMapper;
    private final ProfileSnapshotMapper snapshotMapper;
    private final SettingsMapper settingsMapper;
    private final AiGrpcClient aiGrpcClient;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void chat(UUID userId, ChatRequestDTO dto, SseEmitter emitter) {
        String question = dto.getQuestion().trim();
        try {
            // 1. 会话：不存在则创建（标题取提问截断）
            ChatSession session = resolveSession(userId, dto.getSessionId(), question);

            // 2. user 消息落库（先落，失败重试时历史不丢）
            insertMessage(userId, session.getId(), "user", question, null);

            // 3. 意图抽取（失败兜底 HYBRID）
            MirrorChatProto.ExtractIntentResponse intent = extractIntentSafely(userId, question);
            String route = normalizeRoute(intent.getQueryType());
            sendEvent(emitter, "meta", Map.of(
                    "sessionId", session.getId().toString(),
                    "route", route.toUpperCase()));

            // 4. 四路检索
            List<RetrievedChunkDTO> chunks = retrieve(userId, route, intent);
            log.info("对话检索完成，路由: {}，命中: {}", route, chunks.size());

            // 5. 检索为空兜底（6.6）
            if (chunks.isEmpty()) {
                finishWithFallback(emitter, userId, session.getId(), route, FALLBACK_NO_RECORDS);
                return;
            }

            // 6. 流式 Chat 透传
            streamAnswer(emitter, userId, session.getId(), route, question, intent, chunks);
        } catch (Exception e) {
            log.error("对话处理失败，用户: {}", userId, e);
            try {
                sendEvent(emitter, "error", Map.of("message", FALLBACK_NO_ANSWER));
            } catch (Exception ignored) {
                // emitter 已失效（客户端断开）
            }
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 流式回答：gRPC Chat 服务端流逐块转发为 SSE delta，结束后落库 assistant 消息 + 触碰 session
     */
    private void streamAnswer(SseEmitter emitter, UUID userId, UUID sessionId, String route,
                              String question, MirrorChatProto.ExtractIntentResponse intent,
                              List<RetrievedChunkDTO> chunks) {
        MirrorChatProto.ChatRequest request = buildChatRequest(userId, question, intent, chunks, sessionId);
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> sources = null;
        try {
            Iterator<MirrorChatProto.ChatChunk> stream = aiGrpcClient.chatStream(userId, request);
            while (stream.hasNext()) {
                MirrorChatProto.ChatChunk chunk = stream.next();
                if (!chunk.getContent().isEmpty()) {
                    answer.append(chunk.getContent());
                    sendEvent(emitter, "delta", Map.of("content", chunk.getContent()));
                }
                if (chunk.getDone()) {
                    sources = extractSources(chunk, chunks);
                }
            }
        } catch (Exception e) {
            log.error("Chat 流式调用失败，会话: {}", sessionId, e);
        }

        if (answer.isEmpty()) {
            // AI 失败兜底（6.6）：仍落库，保证会话连续
            finishWithFallback(emitter, userId, sessionId, route, FALLBACK_NO_ANSWER);
            return;
        }

        // assistant 消息落库（sources 落库，裁决 #8）+ 触碰 session.updated_at
        if (sources == null) {
            sources = deriveSources(chunks);
        }
        insertMessage(userId, sessionId, "assistant", answer.toString(), sources);
        touchSession(sessionId);
        sendEvent(emitter, "sources", sources);
        sendEvent(emitter, "done", Map.of(
                "sessionId", sessionId.toString(),
                "route", route.toUpperCase()));
    }

    /**
     * 四路检索路由（设计文档 6.6）
     */
    private List<RetrievedChunkDTO> retrieve(UUID userId, String route,
                                             MirrorChatProto.ExtractIntentResponse intent) {
        return switch (route) {
            case "profile" -> retrieveProfile(userId, intent);
            case "structured" -> searchMapper.searchStructured(userId,
                    emptyToNull(intent.getContentType()),
                    intent.getMoodsList(),
                    toPgTextArray(intent.getMoodsList()),
                    timeStart(intent.getTimeRange()), timeEnd(intent.getTimeRange()),
                    CONTEXT_CHUNK_LIMIT);
            case "semantic" -> searchSemantic(userId, intent);
            default -> searchHybrid(userId, intent);
        };
    }

    /**
     * PROFILE：查最新 2 份快照作上下文；查不到 fallback HYBRID（6.6）
     */
    private List<RetrievedChunkDTO> retrieveProfile(UUID userId,
                                                    MirrorChatProto.ExtractIntentResponse intent) {
        List<ProfileSnapshot> snapshots = new ArrayList<>();
        List<ProfileSnapshot> manual = snapshotMapper.selectRecent(userId, "manual", SNAPSHOT_LIMIT);
        snapshots.addAll(manual);
        if (snapshots.size() < SNAPSHOT_LIMIT) {
            snapshots.addAll(snapshotMapper.selectRecent(userId, "monthly",
                    SNAPSHOT_LIMIT - snapshots.size()));
        }
        if (snapshots.isEmpty()) {
            log.info("无画像快照，PROFILE 回退 HYBRID");
            return searchHybrid(userId, intent);
        }
        List<RetrievedChunkDTO> result = new ArrayList<>();
        for (ProfileSnapshot snapshot : snapshots) {
            StringBuilder text = new StringBuilder();
            text.append("画像快照（").append(snapshot.getSnapshotType()).append("，")
                    .append(snapshot.getCreatedAt() == null ? ""
                            : snapshot.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .append("）：");
            if (notBlank(snapshot.getOverallSummary())) text.append(snapshot.getOverallSummary());
            if (notBlank(snapshot.getMoodAnalysis())) text.append(" 情绪：").append(snapshot.getMoodAnalysis());
            if (notBlank(snapshot.getLearningAnalysis())) text.append(" 学习：").append(snapshot.getLearningAnalysis());
            if (notBlank(snapshot.getTodoAnalysis())) text.append(" 待办：").append(snapshot.getTodoAnalysis());
            if (notBlank(snapshot.getRhythmAnalysis())) text.append(" 节奏：").append(snapshot.getRhythmAnalysis());
            // record_id=0 表示画像伪 chunk：不进 sources（前端点击溯源只针对真实记录）
            result.add(RetrievedChunkDTO.builder()
                    .recordId(0L)
                    .content(text.toString())
                    .title("镜子画像")
                    .createdAt(snapshot.getCreatedAt() == null ? ""
                            : snapshot.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .contentType("profile")
                    .score(0.0)
                    .build());
        }
        return result;
    }

    /**
     * SEMANTIC：Embed 改写 query → 纯向量检索（time_range 非空时关衰减）
     */
    private List<RetrievedChunkDTO> searchSemantic(UUID userId,
                                                   MirrorChatProto.ExtractIntentResponse intent) {
        String vector = embedQueryVector(userId, rewritten(intent));
        boolean decay = !hasTimeRange(intent.getTimeRange());
        return searchMapper.searchSemantic(userId, vector, decay, halfLife(userId), CONTEXT_CHUNK_LIMIT);
    }
    /**
     * HYBRID：元数据预过滤 + 向量 + 时间衰减；time_range 解析成功时同时收窄时间窗
     */
    private List<RetrievedChunkDTO> searchHybrid(UUID userId,
                                                 MirrorChatProto.ExtractIntentResponse intent) {
        String vector = embedQueryVector(userId, rewritten(intent));
        boolean decay = !hasTimeRange(intent.getTimeRange());
        OffsetDateTime start = timeStart(intent.getTimeRange());
        OffsetDateTime end = timeEnd(intent.getTimeRange());
        return searchMapper.searchHybrid(userId, vector,
                emptyToNull(intent.getContentType()),
                intent.getMoodsList(),
                toPgTextArray(intent.getMoodsList()),
                start, end,
                decay, halfLife(userId), CONTEXT_CHUNK_LIMIT);
    }

    /**
     * 组装 ChatRequest：question + 截断后的 history（最近 3 轮）+ chunks + profile 伪 chunk 排除
     */
    private MirrorChatProto.ChatRequest buildChatRequest(UUID userId, String question,
                                                         MirrorChatProto.ExtractIntentResponse intent,
                                                         List<RetrievedChunkDTO> chunks,
                                                         UUID sessionId) {
        MirrorChatProto.ChatRequest.Builder builder = MirrorChatProto.ChatRequest.newBuilder()
                .setQuestion(question);

        // 对话历史最近 3 轮（6 条），时间正序
        List<ConversationHistory> recent = historyMapper.selectRecent(sessionId, userId, HISTORY_ROUNDS * 2);
        Collections.reverse(recent);
        for (ConversationHistory h : recent) {
            builder.addHistory(MirrorChatProto.ChatMessage.newBuilder()
                    .setRole(h.getRole())
                    .setContent(h.getContent()));
        }

        for (RetrievedChunkDTO c : chunks) {
            builder.addChunks(MirrorChatProto.RetrievedChunk.newBuilder()
                    .setRecordId(c.getRecordId() == null ? 0 : c.getRecordId())
                    .setContent(nullToEmpty(c.getContent()))
                    .setTitle(nullToEmpty(c.getTitle()))
                    .setCreatedAt(nullToEmpty(c.getCreatedAt()))
                    .setContentType(nullToEmpty(c.getContentType()))
                    .setScore(c.getScore() == null ? 0f : c.getScore().floatValue()));
        }
        return builder.build();
    }

    /**
     * SSE 兜底收尾：推送兜底文案 + assistant 消息落库（无 sources）+ 触碰 session
     */
    private void finishWithFallback(SseEmitter emitter, UUID userId, UUID sessionId,
                                    String route, String fallbackText) {
        try {
            sendEvent(emitter, "delta", Map.of("content", fallbackText));
        } catch (Exception ignored) {
        }
        insertMessage(userId, sessionId, "assistant", fallbackText, null);
        touchSession(sessionId);
        try {
            sendEvent(emitter, "done", Map.of(
                    "sessionId", sessionId.toString(),
                    "route", route.toUpperCase(),
                    "fallback", true));
        } catch (Exception ignored) {
        }
    }

    // ==================== 会话管理 ====================

    @Override
    public List<ChatSessionVO> listSessions(UUID userId) {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdatedAt));
        return sessions.stream().map(s -> ChatSessionVO.builder()
                .id(s.getId())
                .title(s.getTitle())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build()).toList();
    }

    @Override
    public ChatSessionVO getSession(UUID sessionId, UUID userId) {
        ChatSession session = requireOwnedSession(sessionId, userId);
        List<ChatSessionVO.MessageVO> messages = historyMapper.selectBySession(sessionId, userId)
                .stream().map(h -> ChatSessionVO.MessageVO.builder()
                        .id(h.getId())
                        .role(h.getRole())
                        .content(h.getContent())
                        .sources(h.getSources())
                        .createdAt(h.getCreatedAt())
                        .build()).toList();
        return ChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .messages(messages)
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    @Override
    public void deleteSession(UUID sessionId, UUID userId) {
        requireOwnedSession(sessionId, userId);
        sessionMapper.deleteById(sessionId); // conversation_history 级联删除（FK ON DELETE CASCADE）
        log.info("会话已删除，ID: {}, 用户: {}", sessionId, userId);
    }

    // ==================== 内部方法 ====================

    private ChatSession resolveSession(UUID userId, UUID sessionId, String question) {
        if (sessionId != null) {
            ChatSession existing = requireOwnedSession(sessionId, userId);
            touchSession(existing.getId());
            return existing;
        }
        ChatSession session = ChatSession.builder()
                .id(UUID.randomUUID()) // 表主键无默认值（未用 gen_random_uuid()），应用侧生成
                .userId(userId)
                .title(question.length() > TITLE_MAX_LEN ? question.substring(0, TITLE_MAX_LEN) : question)
                .createdAt(OffsetDateTime.now(ZONE))
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        sessionMapper.insert(session);
        log.info("新会话已创建，ID: {}, 用户: {}", session.getId(), userId);
        return session;
    }

    private ChatSession requireOwnedSession(UUID sessionId, UUID userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private void insertMessage(UUID userId, UUID sessionId, String role, String content,
                               List<Map<String, Object>> sources) {
        ConversationHistory message = ConversationHistory.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role(role)
                .content(content)
                .sources(sources)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        historyMapper.insert(message);
    }

    private void touchSession(UUID sessionId) {
        ChatSession patch = new ChatSession();
        patch.setId(sessionId);
        patch.setUpdatedAt(OffsetDateTime.now(ZONE));
        sessionMapper.updateById(patch);
    }

    private MirrorChatProto.ExtractIntentResponse extractIntentSafely(UUID userId, String question) {
        try {
            return aiGrpcClient.extractIntent(userId, question);
        } catch (Exception e) {
            log.warn("ExtractIntent 失败，回退 HYBRID，用户: {}，原因: {}", userId, e.getMessage());
            return MirrorChatProto.ExtractIntentResponse.newBuilder().setQueryType("hybrid").build();
        }
    }

    private String normalizeRoute(String queryType) {
        String route = queryType == null ? "" : queryType.trim().toLowerCase();
        return switch (route) {
            case "profile", "structured", "semantic" -> route;
            default -> "hybrid";
        };
    }

    private String embedQueryVector(UUID userId, String query) {
        EmbeddingProto.EmbedResponse response = aiGrpcClient.embed(userId, query);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < response.getVectorCount(); i++) {
            if (i > 0) sb.append(',');
            sb.append(response.getVector(i));
        }
        return sb.append(']').toString();
    }

    private double halfLife(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        Integer days = settings == null ? null : settings.getRagHalfLife();
        return days == null || days <= 0 ? DEFAULT_HALF_LIFE : days;
    }

    private String rewritten(MirrorChatProto.ExtractIntentResponse intent) {
        return notBlank(intent.getRewrittenQuery()) ? intent.getRewrittenQuery().trim() : null;
    }

    private boolean hasTimeRange(String timeRange) {
        return timeRange != null && !timeRange.isBlank();
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase();
    }

    /**
     * moods 列表 → jsonb_exists_any 的 text[] 字面量（如 {"happy","calm"}）。
     * （E2E 联调修复：pgjdbc 把 jsonb 的 ?| 操作符当占位符解析，改用
     * jsonb_exists_any(metadata->'mood', ?::text[]) 函数形式，占位符不再歧义。）
     */
    private String toPgTextArray(List<String> moods) {
        if (moods == null || moods.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < moods.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(moods.get(i).replace("\"", "")).append('"');
        }
        return sb.append('}').toString();
    }

    /**
     * time_range 硬过滤起始时间（解析失败返回 null → 不加时间条件）
     *
     * <p>pragmatic 解析：N 天/周/月 + 今天/昨天/本周/上周/今年。
     * 无论是否解析成功，time_range 非空即关闭衰减（用户点名了时间，不应降权）。</p>
     */
    private OffsetDateTime timeStart(String timeRange) {
        if (!hasTimeRange(timeRange)) {
            return null;
        }
        String s = timeRange.trim();
        LocalDate today = LocalDate.now(ZONE);
        long n = extractNumber(s);
        if (s.contains("今天") || s.contains("今日")) {
            return today.atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("昨天") || s.contains("昨日")) {
            return today.minusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("今年")) {
            return today.withDayOfYear(1).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("上周")) {
            return today.minusDays(n > 0 ? n * 7 + 7 : 14).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("周") || s.contains("星期")) {
            return today.minusDays(n > 0 ? n * 7 : 7).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("月")) {
            return today.minusMonths(n > 0 ? n : 1).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("天") || s.contains("日")) {
            return today.minusDays(n > 0 ? n : 7).atStartOfDay(ZONE).toOffsetDateTime();
        }
        return null;
    }

    /**
     * time_range 硬过滤结束时间：仅"昨天/上周"这类明确排除今天的区间需要（其余为开区间到未来）
     */
    private OffsetDateTime timeEnd(String timeRange) {
        if (!hasTimeRange(timeRange)) {
            return null;
        }
        String s = timeRange.trim();
        if (s.contains("昨天") || s.contains("昨日")) {
            return LocalDate.now(ZONE).atStartOfDay(ZONE).toOffsetDateTime();
        }
        if (s.contains("上周")) {
            return LocalDate.now(ZONE).minusDays(7).atStartOfDay(ZONE).toOffsetDateTime();
        }
        return null;
    }

    private long extractNumber(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.isEmpty()) {
            // 中文数字支持（一~十）
            String[] cn = {"零", "一", "两", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
            for (int i = cn.length - 1; i >= 1; i--) {
                if (s.contains(cn[i])) {
                    return i;
                }
            }
            return -1;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 从 ChatChunk.done 提取 Python 返回的 sources；为空时用本地检索结果兜底推导
     */
    private List<Map<String, Object>> extractSources(MirrorChatProto.ChatChunk chunk,
                                                     List<RetrievedChunkDTO> chunks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MirrorChatProto.Source s : chunk.getSourcesList()) {
            if (s.getRecordId() <= 0) {
                continue; // 画像伪 chunk 不进 sources
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("record_id", s.getRecordId());
            map.put("quote", truncate(s.getQuote()));
            map.put("date", s.getDate());
            result.add(map);
        }
        return result.isEmpty() ? deriveSources(chunks) : result;
    }

    /**
     * 本地兜底 sources：取前 3 条真实记录 chunk（record_id>0）
     */
    private List<Map<String, Object>> deriveSources(List<RetrievedChunkDTO> chunks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RetrievedChunkDTO c : chunks) {
            if (c.getRecordId() == null || c.getRecordId() <= 0) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("record_id", c.getRecordId());
            map.put("quote", truncate(c.getTitle() != null && !c.getTitle().isBlank()
                    ? c.getTitle() : c.getContent()));
            map.put("date", c.getCreatedAt() != null && c.getCreatedAt().length() >= 10
                    ? c.getCreatedAt().substring(0, 10) : "");
            result.add(map);
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() > QUOTE_MAX_LEN ? trimmed.substring(0, QUOTE_MAX_LEN) : trimmed;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
