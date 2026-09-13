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
import org.xianshen.mumirrorb.grpc.GlossaryProtoMapper;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
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
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.GlossaryService;

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
    private static final int HISTORY_ROUNDS = 3;           // 对话历史最近 3 轮（设计 6.2：6 条消息）
    // ↑ 早期放到 20 轮（实际送 40 条）反而有害：模型会顺着上文的结论和措辞继续说，
    //   上一轮跑偏的因果会被"接着上文"放大；同时挤占 prompt token。追问所需的上文 3 轮足够。
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
    private final GlossaryService glossaryService;
    private final org.xianshen.mumirrorb.tools.ToolOrchestrator toolOrchestrator;
    private final ObjectMapper objectMapper;
    private final org.xianshen.mumirrorb.config.MirrorProperties mirrorProperties;

    @Override
    @Async
    public void chat(UUID userId, ChatRequestDTO dto, SseEmitter emitter) {
        String question = dto.getQuestion().trim();
        try {
            // 1. 会话：不存在则创建（标题取提问截断）
            ChatSession session = resolveSession(userId, dto.getSessionId(), question);

            // 2. user 消息落库（先落，失败重试时历史不丢）
            insertMessage(userId, session.getId(), "user", question, null);

            // 3. 意图抽取（失败兜底 HYBRID）；query 侧词表命中随请求注入（第 4 节）
            List<UserTermVO> matchedTerms = matchQueryTermsSafely(userId, question);
            MirrorChatProto.ExtractIntentResponse intent =
                    extractIntentSafely(userId, question, GlossaryProtoMapper.toProtoList(matchedTerms));
            String route = normalizeRoute(intent.getQueryType());

            // 3.5 PlanTools 编排（toolcalling-vault-design.md 第 1 节）：失败/空计划 → 空列表走纯 RAG，零回归
            List<CommonProto.ToolResult> toolResults =
                    toolOrchestrator.planAndExecute(userId, session.getId(), question);
            java.util.List<String> toolsUsed = toolResults.stream()
                    .filter(org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolResult::getSuccess)
                    .map(CommonProto.ToolResult::getSummary)
                    .toList();
            // 工具轨迹落库（[{tool, summary}]）：历史回放时前端据此还原工具轨迹芯片
            java.util.List<Map<String, Object>> toolsUsedRecords = toolsUsedRecords(toolResults);
            if (!toolsUsed.isEmpty()) {
                sendEvent(emitter, "meta", Map.of(
                        "sessionId", session.getId().toString(),
                        "route", route.toUpperCase(),
                        "tools_used", toolsUsed));
            }
            sendEvent(emitter, "meta", Map.of(
                    "sessionId", session.getId().toString(),
                    "route", route.toUpperCase()));

            // 4. 四路检索
            List<RetrievedChunkDTO> chunks = retrieve(userId, route, intent);
            log.info("对话检索完成，路由: {}，命中: {}", route, chunks.size());

            // 5. 检索为空兜底（6.6）：工具已查得实质数据时仍放行到 LLM——
            // "我传过的开题报告在哪"这类问题本就可由 find_item 独立回答，提前 return 会把
            // toolResults 连同 vault_refs 文件卡一起丢掉（工具白跑 + 兜底文案答非所问）。
            if (chunks.isEmpty() && !hasUsableToolData(toolResults)) {
                finishWithFallback(emitter, userId, session.getId(), route, FALLBACK_NO_RECORDS);
                return;
            }

            // 6. 流式 Chat 透传（带工具结果）
            streamAnswer(emitter, userId, session.getId(), route, question, intent, chunks,
                    toolResults, toolsUsedRecords);
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
                              List<RetrievedChunkDTO> chunks,
                              List<CommonProto.ToolResult> toolResults,
                              List<Map<String, Object>> toolsUsedRecords) {
        MirrorChatProto.ChatRequest request = buildChatRequest(userId, question, intent, chunks, sessionId, toolResults);
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> sources = null;
        try {
            Iterator<MirrorChatProto.ChatChunk> stream = aiGrpcClient.chatStream(userId, request);
            while (stream.hasNext()) {
                MirrorChatProto.ChatChunk chunk = stream.next();
                // thinking 透传（与 AI 仓 mirror_chat.proto ChatChunk.thinking=4 对齐）：
                // Python 捕获 LLM 思考流（Anthropic thinking_delta / OpenAI reasoning_content），
                // 仅非空才发（不产生空事件）；SSE 事件名 thinking，data {"content": "..."}，与 delta 同构
                if (chunk.hasThinking() && !chunk.getThinking().isEmpty()) {
                    sendEvent(emitter, "thinking", Map.of("content", chunk.getThinking()));
                }
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

        // assistant 消息落库（sources/tools_used/vault_refs：裁决 #8 + 历史回放契约）+ 触碰 session
        if (sources == null) {
            sources = List.of(); // AI 没回 done 块（流中断）→ 没有来源信息，不猜
        }
        // vault_refs（toolcalling-vault-design.md 4.1 + fix-batch B3）两路合并：
        // ① 工具侧 [F编号] 引用 ② 通用检索命中的 vault keyChunk（弱引用）
        java.util.List<Map<String, Object>> vaultRefs =
                vaultRefsFromChunks(chunks, extractVaultRefs(answer.toString(), toolResults));
        insertMessage(userId, sessionId, "assistant", answer.toString(), sources,
                toolsUsedRecords, vaultRefs);
        touchSession(sessionId);
        sendEvent(emitter, "sources", sources);
        if (!vaultRefs.isEmpty()) {
            sendEvent(emitter, "vault_refs", vaultRefs);
        }
        sendEvent(emitter, "done", Map.of(
                "sessionId", sessionId.toString(),
                "route", route.toUpperCase()));
    }

    /**
     * vault_refs 提取（4.1）：扫描 AI 回答中的 [F编号] 标记（fix-batch B3/Y3：
     * 文件引用独立编号空间 [F1][F2]…，与日记资料 sources 的 [n] 不再共用——
     * 普通 [1] 不再触发文件卡，避免与 sources 引用撞编号）。
     *
     * <p>Fn 落在 1..vault 文件数 内 → 取 find_item/recall_item 结果对应项组文件卡（同气泡去重）。</p>
     */
    private java.util.List<Map<String, Object>> extractVaultRefs(String answer,
                                                                 List<CommonProto.ToolResult> toolResults) {
        java.util.List<Map<String, Object>> refs = new ArrayList<>();
        if (answer == null || toolResults == null || toolResults.isEmpty()) {
            return refs;
        }
        // 收集 vault 工具结果的 items（find_item 的 items / recall_item 的 item）
        java.util.List<Map<String, Object>> vaultItems = new ArrayList<>();
        for (CommonProto.ToolResult tr : toolResults) {
            if (!tr.getSuccess() || tr.getPayloadJson().isBlank()) {
                continue;
            }
            try {
                Map<String, Object> payload = objectMapper.readValue(tr.getPayloadJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                Object items = payload.get("items");
                if (items instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> item = (Map<String, Object>) m;
                            vaultItems.add(item);
                        }
                    }
                } else if (payload.get("item") instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) m;
                    vaultItems.add(item);
                }
            } catch (Exception ignored) {
            }
        }
        if (vaultItems.isEmpty()) {
            return refs;
        }
        // [F编号] 标记去重收集（1-based；只认 [F\d+]，普通 [n] 是日记 sources 引用不触发文件卡——B3/Y3）
        java.util.Set<Integer> marks = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[F(\\d{1,2})]").matcher(answer);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n >= 1 && n <= vaultItems.size()) {
                    marks.add(n);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.List<Integer> ordered = new ArrayList<>(marks);
        java.util.Collections.sort(ordered);
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (int n : ordered) {
            Map<String, Object> item = vaultItems.get(n - 1);
            Object idObj = item.get("vault_item_id");
            Long id = idObj instanceof Number num ? num.longValue() : null;
            if (id == null || !seen.add(id)) {
                continue; // 同文件多引用 → 同气泡单卡
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("n", n);
            ref.put("vault_item_id", id);
            ref.put("display_name", item.getOrDefault("display_name", item.get("original_name")));
            ref.put("file_type", item.get("file_type"));
            ref.put("size", item.get("size"));
            ref.put("digest_status", item.get("digest_status"));
            ref.put("quote", item.get("quote"));
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 工具是否产出了可独立支撑回答的实质数据（4.1 断环修复）。
     *
     * <p>只认 payload 里"有内容"的值：空列表/空 map/0/空串 一律不算——这样
     * find_item 的 {"count":0,"items":[]}（成功但没找到）不会绕过"没有找到相关记录"兜底，
     * 而真命中的 {"count":1,"items":[{…}]} 会放行到 LLM 并触发 vault_refs 文件卡。</p>
     */
    private boolean hasUsableToolData(List<CommonProto.ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return false;
        }
        for (CommonProto.ToolResult tr : toolResults) {
            if (!tr.getSuccess() || tr.getPayloadJson().isBlank()) {
                continue;
            }
            try {
                Map<String, Object> payload = objectMapper.readValue(tr.getPayloadJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                if (hasSubstance(payload)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 递归判定 JSON 值是否"有实质内容"（null/空容器/0/blank/false 视为无） */
    private boolean hasSubstance(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence cs) {
            return !cs.toString().isBlank();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Map<?, ?> m) {
            return m.values().stream().anyMatch(this::hasSubstance);
        }
        if (value instanceof Iterable<?> it) {
            for (Object o : it) {
                if (hasSubstance(o)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * 四路检索路由（设计文档 6.6）
     */
    private List<RetrievedChunkDTO> retrieve(UUID userId, String route,
                                             MirrorChatProto.ExtractIntentResponse intent) {
        return switch (route) {
            case "profile" -> retrieveProfile(userId, intent);
            case "structured" -> {
                // 结构化路由必须真带元数据条件，否则就退化成"按时间倒序端最近 N 条"
                // （与问题内容完全无关，等于强行喂噪声）。无任何条件时回退 HYBRID
                // 走向量+相关性阈值（同 PROFILE 无快照回退 HYBRID 的口径）。
                boolean hasMetaFilter = emptyToNull(intent.getContentType()) != null
                        || !intent.getMoodsList().isEmpty()
                        || hasTimeRange(intent.getTimeRange());
                if (!hasMetaFilter) {
                    log.info("STRUCTURED 无元数据过滤条件，回退 HYBRID（避免按时间倒序喂噪声）");
                    yield searchHybrid(userId, intent);
                }
                yield searchMapper.searchStructured(userId,
                        emptyToNull(intent.getContentType()),
                        intent.getMoodsList(),
                        toPgTextArray(intent.getMoodsList()),
                        timeStart(intent.getTimeRange()), timeEnd(intent.getTimeRange()),
                        CONTEXT_CHUNK_LIMIT);
            }
            case "semantic" -> searchSemantic(userId, intent);
            default -> searchHybrid(userId, intent);
        };
    }

    /**
     * 相关性下限（余弦距离）：配置 ≤0 表示关闭阈值 → null（SQL 的 if 不拼该条件）。
     * 超过阈值的 chunk 在 SQL 层就被丢弃，全部被丢弃时返回空列表 → 走"没有找到相关记录"兜底。
     */
    private Double maxCosineDistance() {
        double configured = mirrorProperties.getRagMaxCosineDistance();
        return configured > 0 ? configured : null;
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
            // score=-1：画像快照是整段上下文而非检索命中，无相似度可言（哨兵值约定见 ChatSearchMapper）
            result.add(RetrievedChunkDTO.builder()
                    .recordId(0L)
                    .content(text.toString())
                    .title("镜子画像")
                    .createdAt(snapshot.getCreatedAt() == null ? ""
                            : snapshot.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .contentType("profile")
                    .score(-1.0)
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
        return searchMapper.searchSemantic(userId, vector, decay, halfLife(userId),
                maxCosineDistance(), CONTEXT_CHUNK_LIMIT);
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
                decay, halfLife(userId), maxCosineDistance(), CONTEXT_CHUNK_LIMIT);
    }

    /**
     * 组装 ChatRequest：question + 截断后的 history（最近 3 轮）+ chunks + profile 伪 chunk 排除
     */
    private MirrorChatProto.ChatRequest buildChatRequest(UUID userId, String question,
                                                         MirrorChatProto.ExtractIntentResponse intent,
                                                         List<RetrievedChunkDTO> chunks,
                                                         UUID sessionId,
                                                         List<CommonProto.ToolResult> toolResults) {
        MirrorChatProto.ChatRequest.Builder builder = MirrorChatProto.ChatRequest.newBuilder()
                .setQuestion(question);

        // 工具结果注入（toolcalling-vault-design.md 第 6 节 ChatRequest.tool_results）
        if (toolResults != null && !toolResults.isEmpty()) {
            builder.addAllToolResults(toolResults);
        }

        // 个人词典注入（lexicon-design.md 第 4 节）：Chat 路由传 confirmed top 30 全量（软约束）
        try {
            builder.addAllGlossary(GlossaryProtoMapper.toProtoList(glossaryService.confirmedForInjection(userId)));
        } catch (Exception e) {
            log.warn("Chat 词表注入失败（按无词表继续），用户: {}, 原因: {}", userId, e.getMessage());
        }

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
                    // score 缺省传 -1（无相似度信息哨兵），不能传 0——proto 默认 0 会被
                    // AI 侧换算成"相关度 100%"，等于给噪声盖章认证
                    .setScore(c.getScore() == null ? -1f : c.getScore().floatValue()));
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
                        .toolsUsed(h.getToolsUsed())
                        .vaultRefs(h.getVaultRefs())
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
        insertMessage(userId, sessionId, role, content, sources, null, null);
    }

    private void insertMessage(UUID userId, UUID sessionId, String role, String content,
                               List<Map<String, Object>> sources,
                               List<Map<String, Object>> toolsUsed,
                               List<Map<String, Object>> vaultRefs) {
        ConversationHistory message = ConversationHistory.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role(role)
                .content(content)
                .sources(sources)
                .toolsUsed(toolsUsed)
                .vaultRefs(vaultRefs)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        historyMapper.insert(message);
    }

    /** 工具轨迹落库形态 [{tool, summary}]（前端 normalizeToolsUsed 兼容对象/字符串两种形态） */
    private List<Map<String, Object>> toolsUsedRecords(List<CommonProto.ToolResult> toolResults) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (toolResults == null) {
            return records;
        }
        for (CommonProto.ToolResult tr : toolResults) {
            if (!tr.getSuccess()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", tr.getTool());
            m.put("summary", tr.getSummary());
            records.add(m);
        }
        return records;
    }

    private void touchSession(UUID sessionId) {
        ChatSession patch = new ChatSession();
        patch.setId(sessionId);
        patch.setUpdatedAt(OffsetDateTime.now(ZONE));
        sessionMapper.updateById(patch);
    }

    private MirrorChatProto.ExtractIntentResponse extractIntentSafely(UUID userId, String question,
                                                                      List<CommonProto.GlossaryTerm> matchedTerms) {
        try {
            MirrorChatProto.ExtractIntentResponse resp = aiGrpcClient.extractIntent(userId, question, matchedTerms);
            // 生产防御：gRPC 正常返回但内容为空（不应发生，Python 契约必有 query_type）——
            // 按 ExtractIntent 失败处理，走 fallback HYBRID，不让 null 打穿整条对话
            if (resp == null) {
                throw new IllegalStateException("ExtractIntent 返回为空");
            }
            return resp;
        } catch (Exception e) {
            log.warn("ExtractIntent 失败，回退 HYBRID，用户: {}，原因: {}", userId, e.getMessage());
            // 兜底响应必须带 rewritten_query=原问题（设计 9.2：改写失败兜底原文），
            // 否则 searchHybrid 里 rewritten() 返回 null → embed(null) NPE
            return MirrorChatProto.ExtractIntentResponse.newBuilder()
                    .setQueryType("hybrid")
                    .setRewrittenQuery(question)
                    .build();
        }
    }

    /**
     * query 侧词表匹配（lexicon-design.md 第 4 节）：term/alias 命中 query → 只把命中的传 Python。
     * 未命中返回空（AiGrpcClient 会以 top 高频词 grounding 兜底）。失败不阻断对话主流程。
     */
    private List<UserTermVO> matchQueryTermsSafely(UUID userId, String question) {
        try {
            return glossaryService.matchQueryTerms(userId, question);
        } catch (Exception e) {
            log.warn("词表 query 匹配失败（不影响对话），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
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
     * 从 ChatChunk.done 提取 Python 返回的 sources；为空时用本地检索结果兜底推导。
     *
     * <p>每条都带 n（正文里的 [n]，1-based）：Python 只回被引用的子集、编号会跳号，
     * 前端必须按 n 定位来源，不能用数组下标（sources[n-1] 在引用 [5] 而列表只有 2 项时落空）。</p>
     */
    private List<Map<String, Object>> extractSources(MirrorChatProto.ChatChunk chunk,
                                                     List<RetrievedChunkDTO> chunks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MirrorChatProto.Source s : chunk.getSourcesList()) {
            if (s.getRecordId() <= 0) {
                continue; // 画像伪 chunk 不进 sources
            }
            if (isVaultRecord(chunks, s.getRecordId())) {
                continue; // vault 文件记录不是"日记来源"，由 vault_refs 文件卡承载
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("n", s.getN() > 0 ? s.getN() : null);
            map.put("record_id", s.getRecordId());
            map.put("quote", truncate(s.getQuote()));
            map.put("date", s.getDate());
            result.add(map);
        }
        // 不再用"本地检索结果"兜底：sources 必须严格等于模型实际引用的记录。
        // 模型没写 [n] 就说明它没用这些资料，此时兜底挂前 3 条会让用户误以为回答基于这些日记
        // （实测反馈：回答完全没提日记，底部却挂了 3 条来源芯片）。
        return result;
    }

    /** 该 record_id 在 chunks 里对应的是 vault 文件记录吗 */
    private boolean isVaultRecord(List<RetrievedChunkDTO> chunks, long recordId) {
        for (RetrievedChunkDTO c : chunks) {
            if (c.getRecordId() != null && c.getRecordId() == recordId) {
                return c.getVaultItemId() != null;
            }
        }
        return false;
    }

    /**
     * 通用检索命中的 vault keyChunk → 弱引用文件卡，与工具侧 [F编号] 卡按 vault_item_id 去重合并。
     *
     * <p>用户问"我的项目设计"时，即使 PlanTools 没规划 find_item，向量检索也可能命中该文件的
     * keyChunk（title=文件名）——这时必须出<b>文件卡</b>，而不是把它当成一条日记来源芯片
     * （文件记录没有可打开的日记详情，归到 sources 是错的）。</p>
     *
     * <p>检索侧拿不到 file_type/size，且无 quote → 前端渲染为"弱引用芯片"（点开确认），
     * 与 find_item 的 weak/vague 档位语义一致。</p>
     */
    private List<Map<String, Object>> vaultRefsFromChunks(List<RetrievedChunkDTO> chunks,
                                                          List<Map<String, Object>> fromTools) {
        List<Map<String, Object>> refs = new ArrayList<>(fromTools);
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : refs) {
            Object id = r.get("vault_item_id");
            if (id instanceof Number n) {
                seen.add(n.longValue());
            }
        }
        for (RetrievedChunkDTO c : chunks) {
            if (c.getVaultItemId() == null || !seen.add(c.getVaultItemId())) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("n", null); // 检索侧没有 [F编号]
            ref.put("vault_item_id", c.getVaultItemId());
            ref.put("display_name", notBlank(c.getTitle()) ? c.getTitle() : c.getContent());
            // 能进通用检索即 confirmed（ChatSearchMapper 白名单 keyChunk='true'）
            ref.put("digest_status", "confirmed");
            ref.put("quote", null);
            refs.add(ref);
        }
        return refs;
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
