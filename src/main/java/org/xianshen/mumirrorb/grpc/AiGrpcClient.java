package org.xianshen.mumirrorb.grpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingServiceGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.springframework.context.annotation.Lazy;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * AI gRPC 客户端
 *
 * 封装对 Python AI 服务的 gRPC 调用，Java 端只调这个类。
 * 每次调用从 user_settings 读取用户的模型配置，放入请求中传给 Python。
 *
 * <p>GlossaryService 用 {@code @Lazy} 注入：GlossaryServiceImpl 反向依赖 AiGrpcClient
 * （ExtractTerms RPC），构造器互相引用成环；词表仅对话/分类/画像路径运行期使用，
 * 不会在彼此初始化阶段调用，Lazy 代理安全破环。</p>
 */
@Slf4j
@Component
public class AiGrpcClient {

    private final ManagedChannel channel;
    private final SettingsMapper settingsMapper;
    private final GlossaryService glossaryService;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final ChunkMapper chunkMapper;
    private final RecordContextProperties recordContextProperties;

    /** 记录日期时区（Asia/Shanghai，与全项目口径一致） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private org.xianshen.mumirrorb.service.TodoRegistryService todoRegistryService;

    public AiGrpcClient(ManagedChannel channel,
                        SettingsMapper settingsMapper,
                        @Lazy GlossaryService glossaryService,
                        org.springframework.context.ApplicationContext applicationContext,
                        ChunkMapper chunkMapper,
                        RecordContextProperties recordContextProperties) {
        this.channel = channel;
        this.settingsMapper = settingsMapper;
        this.glossaryService = glossaryService;
        this.applicationContext = applicationContext;
        this.chunkMapper = chunkMapper;
        this.recordContextProperties = recordContextProperties;
    }

    private RecordProcessorGrpc.RecordProcessorBlockingStub recordStub;
    private EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embedStub;
    private MirrorProfileGrpc.MirrorProfileBlockingStub profileStub;
    private MirrorChatGrpc.MirrorChatBlockingStub chatStub;

    @PostConstruct
    public void init() {
        recordStub = RecordProcessorGrpc.newBlockingStub(channel);
        embedStub = EmbeddingServiceGrpc.newBlockingStub(channel);
        profileStub = MirrorProfileGrpc.newBlockingStub(channel);
        chatStub = MirrorChatGrpc.newBlockingStub(channel);
        // TodoRegistryService 懒解析（TodoRegistryServiceImpl 无反向依赖 AiGrpcClient，
        // 但构造期统一注入更稳——启动期初始化顺序解耦）
        this.todoRegistryService = applicationContext.getBean(
                org.xianshen.mumirrorb.service.TodoRegistryService.class);
        log.info("AiGrpcClient 初始化完成");
    }

    /**
     * 调用 AI 分类服务
     *
     * @param userId  用户 ID（用于读取模型配置）
     * @param content 用户输入的文本
     * @return 分类结果（标题、摘要、标签等）
     */
    public RecordProcessorProto.ClassifyResponse classify(UUID userId, String content) {
        return classify(userId, content, false);
    }

    /**
     * 调用 AI 分类服务（可选携带个人词典）
     *
     * <p>词表注入（lexicon-design.md 第 4 节）：Classify 是最大增量注入点——
     * "论文"这类个人指代在分类现场修最便宜。confirmed top 30 全量随请求携带。</p>
     *
     * @param userId      用户 ID（用于读取模型配置）
     * @param content     用户输入的文本
     * @param withGlossary true=携带 confirmed top 30 词表（管道主分类）
     * @return 分类结果（标题、摘要、标签等）
     */
    public RecordProcessorProto.ClassifyResponse classify(UUID userId, String content, boolean withGlossary) {
        return classify(userId, content, withGlossary, null);
    }

    /**
     * 调用 AI 分类服务（可选携带个人词典 + 相对时间参照日期）
     *
     * @param withGlossary  true=携带 confirmed top 30 词表（管道主分类）
     * @param referenceDate 相对时间消解的参照日期 yyyy-MM-dd（取 record.created_at 的日期部分，
     *                      <b>不是 now()</b>——隔天确认时不能把"今天"锚错）；
     *                      null = 不要求 AI 做时间消解（退化为本次改动前的旧行为）
     * @return 分类结果（标题、摘要、标签、时间词替换表等）
     */
    public RecordProcessorProto.ClassifyResponse classify(UUID userId, String content,
                                                          boolean withGlossary, String referenceDate) {
        log.info("gRPC 调用 Classify，用户: {}, 内容长度: {}, 词表: {}, 参照日期: {}",
                userId, content.length(), withGlossary, referenceDate);
        try {
            CommonProto.LlmConfig llmConfig = buildLlmConfig(userId);
            log.debug("Classify LLM 配置: provider={}, model={}, protocol={}",
                    llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getProtocol());

            RecordProcessorProto.ClassifyRequest.Builder requestBuilder = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
                    .setLlmConfig(llmConfig);
            if (referenceDate != null && !referenceDate.isBlank()) {
                requestBuilder.setReferenceDate(referenceDate);
            }
            if (withGlossary) {
                // 词表组装失败按无词表继续（词表错了退化为普通分类，不是灾难 #0.2）
                requestBuilder.addAllGlossary(groundingTerms(userId));
            }
            // open_todos 注入（todo-registry-design.md §3.2 判别期）：未完成待办清单（≤20 条，
            // 最近优先）。组装失败按空清单继续——清单错了退化为 LLM 不知道旧待办，不炸分类。
            requestBuilder.addAllOpenTodos(todoHintTerms(userId));
            // recent_context 注入（近 7 天记录摘要，指代消解语境）：组装失败按空清单继续——
            // 语境缺失退化为普通分类，绝不阻断。（创建路径无需排除 record：此时该 record 尚无 chunk）
            requestBuilder.addAllRecentContext(recentContextHints(userId, null));

            RecordProcessorProto.ClassifyRequest request = requestBuilder.build();

            RecordProcessorProto.ClassifyResponse response = recordStub
                    .withDeadlineAfter(180, TimeUnit.SECONDS)
                    .classify(request);

            // 记录拆分结果
            int itemCount = response.getItemsList().size();
            log.info("Classify 返回: skip={}, 拆分 {} 条", response.getSkip(), itemCount);

            if (!response.getSkip()) {
                for (int i = 0; i < response.getItemsList().size(); i++) {
                    RecordProcessorProto.ClassifyItem item = response.getItemsList().get(i);
                    log.info("  ClassifyItem [{}]: title={}, contentType={}, moods={}, keywords={}, refersTodo={}",
                            i + 1, item.getTitle(), item.getContentType(),
                            item.getMoodsList(), item.getKeywordsList(),
                            item.hasRefersToTodo() ? item.getRefersToTodo().getTodoId() : 0);
                }
            } else {
                log.info("Classify 跳过原因: {}", response.getSkipReason());
            }

            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 调用失败: status={}, 用户: {}", e.getStatus(), userId, e);
            throw e;
        }
    }

    /**
     * 调用 AI 分类服务（单段模式）
     *
     * <p>single=true：禁止拆分，恰好返回 1 条 ClassifyItem（设计文档 4.4）。
     * 用于审核阶段手动新增片段 / confirm 补分类（5.4）。</p>
     *
     * @param userId  用户 ID（用于读取模型配置）
     * @param content 单个片段文本（用户确认过边界的完整片段）
     * @return 分类结果（应恰好 1 条；异常时抛出，由调用方决定是否阻断）
     */
    public RecordProcessorProto.ClassifyResponse classifySingle(UUID userId, String content) {
        return classifySingle(userId, content, null);
    }

    /**
     * 调用 AI 分类服务（单段模式，可排除指定 record 自身）
     *
     * <p>审核补分类（confirmReview）传入 chunk 所属 recordId，组装近 7 天语境时排除该 record 自身，
     * 防止被自己旧标题污染；新增片段路径（addSegment）新 chunk 尚未入库，传所属 recordId 排除同记录
     * 既有 chunk 同样安全。{@code excludeRecordId} 为 null 时不做排除。</p>
     *
     * @param userId          用户 ID（用于读取模型配置）
     * @param content         单个片段文本（用户确认过边界的完整片段）
     * @param excludeRecordId 组装 recent_context 时排除的 recordId（可空）
     * @return 分类结果（应恰好 1 条；异常时抛出，由调用方决定是否阻断）
     */
    public RecordProcessorProto.ClassifyResponse classifySingle(UUID userId, String content, Long excludeRecordId) {
        return classifySingle(userId, content, excludeRecordId, null);
    }

    /**
     * 调用 AI 分类服务（单段模式，可排除指定 record 自身，可带参照日期）
     *
     * @param excludeRecordId 组装 recent_context 时排除的 recordId（可空）
     * @param referenceDate   相对时间消解参照日期 yyyy-MM-dd（该 chunk 所属 record 的 created_at
     *                        日期部分；null = 不做时间消解）
     * @return 分类结果（应恰好 1 条；异常时抛出，由调用方决定是否阻断）
     */
    public RecordProcessorProto.ClassifyResponse classifySingle(UUID userId, String content,
                                                                Long excludeRecordId, String referenceDate) {
        log.info("gRPC 调用 Classify(single=true)，用户: {}, 内容长度: {}, 排除记录: {}, 参照日期: {}",
                userId, content.length(), excludeRecordId, referenceDate);
        try {
            CommonProto.LlmConfig llmConfig = buildLlmConfig(userId);

            RecordProcessorProto.ClassifyRequest.Builder requestBuilder = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
                    .setLlmConfig(llmConfig)
                    .setSingle(true)
                    .addAllRecentContext(recentContextHints(userId, excludeRecordId));
            if (referenceDate != null && !referenceDate.isBlank()) {
                requestBuilder.setReferenceDate(referenceDate);
            }

            RecordProcessorProto.ClassifyRequest request = requestBuilder.build();

            RecordProcessorProto.ClassifyResponse response = recordStub
                    .withDeadlineAfter(180, TimeUnit.SECONDS)
                    .classify(request);

            log.info("Classify(single) 返回: skip={}, 条数: {}", response.getSkip(), response.getItemsCount());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify(single) 调用失败: status={}, 用户: {}", e.getStatus(), userId, e);
            throw e;
        }
    }

    /**
     * 调用 Embedding 服务
     *
     * @param userId 用户 ID（用于读取 Embedding 配置）
     * @param text   要向量化的文本
     * @return 向量化结果
     */
    public EmbeddingProto.EmbedResponse embed(UUID userId, String text) {
        // 防御：query 为空直接抛业务异常（调用方兜底），避免 NPE 打穿 SSE 流
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed 入参 text 为空");
        }
        log.info("gRPC 调用 Embed，用户: {}, 文本长度: {}", userId, text.length());
        try {
            CommonProto.EmbeddingConfig embedConfig = buildEmbeddingConfig(userId);

            EmbeddingProto.EmbedRequest request = EmbeddingProto.EmbedRequest.newBuilder()
                    .setText(text)
                    .setEmbeddingConfig(embedConfig)
                    .build();

            // 10s → 30s（2026-09-21 联调后整体放宽）：api 向量模型走公网，偶发慢响应不该让整轮对话报错
            EmbeddingProto.EmbedResponse response = embedStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .embed(request);

            log.info("Embed 返回: dimension={}, model={}",
                    response.getDimension(), response.getModelName());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC Embed 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }

    /**
     * 查询 Embedding 模型信息（连接测试 / 维度校验用）
     *
     * <p>对应 Python 端 GetModelInfo RPC；用于 test-embedding 维度校验（裁决 #18：非 1024 维拒绝）。</p>
     *
     * @param userId 用户 ID（用于读取 Embedding 配置）
     * @return 模型信息（model_name / source / dimension / available）
     */
    public EmbeddingProto.ModelInfoResponse getModelInfo(UUID userId) {
        log.info("gRPC 调用 GetModelInfo，用户: {}", userId);
        try {
            EmbeddingProto.ModelInfoRequest.Builder reqBuilder = EmbeddingProto.ModelInfoRequest.newBuilder();
            // field 1（2026-09-04 shared-protocol.md 登记）：api 模式下携带用户 EmbeddingConfig，
            // Python 端按用户配置的模型返回维度，维度校验（裁决 #18）不再失真；
            // local 模式不携带（Python 默认本地 BGE-m3=1024）。
            if ("api".equalsIgnoreCase(buildEmbeddingConfigSource(userId))) {
                reqBuilder.setEmbeddingConfig(buildEmbeddingConfig(userId));
            }
            return embedStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getModelInfo(reqBuilder.build());
        } catch (StatusRuntimeException e) {
            log.error("gRPC GetModelInfo 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }

    /**
     * 读取用户 Embedding source（local / api），不重复构建完整配置
     */
    private String buildEmbeddingConfigSource(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>()
                        .eq(UserSettings::getUserId, userId)
        );
        return settings == null || settings.getEmbeddingSource() == null
                ? "local"
                : settings.getEmbeddingSource();
    }

    /**
     * 调用画像生成服务（设计文档 6.5，阶段 3）
     *
     * <p>输入五维统计 + recent_chats + LlmConfig，输出六维分析 + user_tags + overall_summary。
     * 超时 60s（7.4）。</p>
     *
     * @param userId  用户 ID（读取 LLM 配置）
     * @param request 已组装的 GenerateProfileRequest
     * @return 画像分析结果
     */
    public MirrorProfileProto.GenerateProfileResponse generateProfile(
            UUID userId, MirrorProfileProto.GenerateProfileRequest request) {
        log.info("gRPC 调用 GenerateProfile，用户: {}", userId);
        try {
            MirrorProfileProto.GenerateProfileRequest enriched = request.toBuilder()
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            MirrorProfileProto.GenerateProfileResponse response = profileStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
                    .generateProfile(enriched);
            log.info("GenerateProfile 返回: tags={}, summary 长度={}",
                    response.getUserTagsList(), response.getOverallSummary().length());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC GenerateProfile 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }

    /**
     * 非流式 Chat（每日总结 / 写作灵感复用，设计文档 6.7 / 6.8）
     *
     * <p>消费完整流后返回全文，供定时任务等非流式场景使用。
     * 超时 60s（7.4）。</p>
     *
     * @param userId  用户 ID（读取 LLM 配置）
     * @param request 已组装的 ChatRequest（history / chunks / llm_config）
     * @return 完整回答文本
     */
    public String chatBlocking(UUID userId, MirrorChatProto.ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        Iterator<MirrorChatProto.ChatChunk> stream = chatStream(userId, request);
        while (stream.hasNext()) {
            MirrorChatProto.ChatChunk chunk = stream.next();
            if (!chunk.getContent().isEmpty()) {
                sb.append(chunk.getContent());
            }
        }
        return sb.toString();
    }

    /**
     * 流式 Chat（对话透传给前端，设计文档 6.6）
     *
     * <p>返回阻塞迭代器（服务端流），由调用方（SSE Controller）逐块消费转发。
     * 超时 60s（7.4）；llm_config 在此统一补齐。</p>
     *
     * @param userId  用户 ID（读取 LLM 配置）
     * @param request 已组装的 ChatRequest（question / history / chunks，不含 llm_config）
     * @return ChatChunk 流迭代器（消费完 hasNext() 返回 false；done=true 的块含 sources）
     */
    public Iterator<MirrorChatProto.ChatChunk> chatStream(UUID userId, MirrorChatProto.ChatRequest request) {
        log.info("gRPC 调用 Chat(stream)，用户: {}", userId);
        try {
            MirrorChatProto.ChatRequest enriched = request.toBuilder()
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            // 60s → 180s（2026-09-21 联调）：回答阶段思考预算 2048，mimo 约 36 token/s 光思考就 ~57s，
            // 加正文必然擦着 60s；这是整条回答流的总时长，不是块间间隔（块间由 Python 侧 60s 控制）
            return chatStub
                    .withDeadlineAfter(180, TimeUnit.SECONDS)
                    .chat(enriched);
        } catch (StatusRuntimeException e) {
            log.error("gRPC Chat(stream) 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }

    /**
     * 意图抽取（对话第一步路由，设计文档 6.6）
     *
     * <p>返回 query_type（profile/structured/semantic/hybrid）+ 过滤条件 + 改写 query。
     * 超时 15s（7.4）。失败由调用方兜底：route 回退 HYBRID。</p>
     *
     * <p>词表注入（lexicon-design.md 第 4 节）：B 侧 query 匹配命中的词条随请求携带
     * （只传命中项省 prompt）；命中为空时传 top 高频词作 grounding（未命中传全量 top30）。</p>
     *
     * @param userId       用户 ID（读取 LLM 配置）
     * @param query        用户原始提问
     * @param matchedTerms query 侧命中的个人词典词条（可空=未命中，传 top 高频词）
     * @return ExtractIntentResponse（query_type / content_type / moods / time_range / rewritten_query）
     */
    public MirrorChatProto.ExtractIntentResponse extractIntent(UUID userId, String query,
                                                               List<CommonProto.GlossaryTerm> matchedTerms) {
        log.info("gRPC 调用 ExtractIntent，用户: {}, query: {}, 词表命中: {} 条",
                userId, query, matchedTerms == null ? 0 : matchedTerms.size());
        try {
            MirrorChatProto.ExtractIntentRequest.Builder requestBuilder = MirrorChatProto.ExtractIntentRequest.newBuilder()
                    .setQuery(query)
                    .setLlmConfig(buildLlmConfig(userId));
            if (matchedTerms != null && !matchedTerms.isEmpty()) {
                // 命中：只传命中的词条（省 prompt，#4）
                requestBuilder.addAllGlossary(matchedTerms);
            } else {
                // 未命中：top 高频词 grounding（让 Python 知道这些词的个人含义，防止误改写）
                requestBuilder.addAllGlossary(groundingTerms(userId));
            }
            // 35s → 60s（2026-09-21 联调后整体放宽；Python 侧非流式总超时同步放到 60s）
            MirrorChatProto.ExtractIntentResponse response = chatStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
                    .extractIntent(requestBuilder.build());
            log.info("ExtractIntent 返回: queryType={}, rewrittenQuery={}",
                    response.getQueryType(), response.getRewrittenQuery());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC ExtractIntent 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }

    /**
     * 兼容旧签名：不带词表的意图抽取（无命中/词表关闭场景的短路重载）
     */
    public MirrorChatProto.ExtractIntentResponse extractIntent(UUID userId, String query) {
        return extractIntent(userId, query, List.of());
    }

    /**
     * grounding 词条：confirmed top 30（proto GlossaryTerm）
     */
    private List<CommonProto.GlossaryTerm> groundingTerms(UUID userId) {
        try {
            return GlossaryProtoMapper.toProtoList(glossaryService.confirmedForInjection(userId));
        } catch (Exception e) {
            log.warn("词表 grounding 组装失败（按空处理），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * open_todos 组装（todo-registry-design.md §3.2 判别期）
     *
     * <p>查 todo_registry（current_status != 'completed'，最近优先，≤20 条，JOIN chunks 排 orphan），
     * 每条带 title + 原始片段摘要（100 字符）+ created_at + current_status。
     * todoRegistryService 依赖 @Lazy（与 RecordServiceImpl 的管道依赖共存，避免启动期初始化顺序纠缠）；
     * 组装失败按空清单继续（旧待办不注入，分类照常）。</p>
     */
    private List<RecordProcessorProto.TodoHint> todoHintTerms(UUID userId) {
        try {
            List<org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO.TodoItem> items =
                    todoRegistryService.openTodosForHint(userId);
            List<RecordProcessorProto.TodoHint> hints = new java.util.ArrayList<>(items.size());
            for (org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO.TodoItem item : items) {
                RecordProcessorProto.TodoHint.Builder builder = RecordProcessorProto.TodoHint.newBuilder()
                        .setTodoId(item.getTodoId() == null ? 0 : item.getTodoId())
                        .setTitle(item.getTitle() == null ? "" : item.getTitle())
                        .setCreatedAt(item.getCreatedAt() == null ? "" : item.getCreatedAt())
                        .setCurrentStatus(item.getCurrentStatus() == null ? "not_started" : item.getCurrentStatus());
                String excerpt = item.getSourceExcerpt() != null ? item.getSourceExcerpt()
                        : item.getSourceSummary();
                if (excerpt != null && !excerpt.isBlank()) {
                    builder.setSourceExcerpt(excerpt);
                }
                hints.add(builder.build());
            }
            return hints;
        } catch (Exception e) {
            log.warn("open_todos 组装失败（按空清单继续），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * recent_context 组装（近 7 天记录摘要，分类指代消解语境）
     *
     * <p>查 chunks.metadata（口径同 selectCorrectionIndex：user 记录、未删除、非 failed、非 vault、
     * title 非空），按时间倒序取 SQL 上限 {@code maxQuery} 条；Java 侧按 title 去重（SQL 已倒序，
     * 首次出现即最新），最终最多 {@code maxHints} 条，每条 keywords 取前 {@code maxKeywords} 个。
     * {@code excludeRecordId} 非空时排除该 record 自身（审核补分类防旧标题自污染）。</p>
     *
     * <p>整体 try-catch：SQL 异常 / JSON 解析异常均降级为空清单并打 warn，绝不阻断分类。</p>
     */
    private List<RecordProcessorProto.RecentHint> recentContextHints(UUID userId, Long excludeRecordId) {
        try {
            OffsetDateTime since = OffsetDateTime.now(ZONE)
                    .minusDays(recordContextProperties.getWindowDays());
            List<Map<String, Object>> rows = chunkMapper.selectRecentContextHints(
                    userId, since, excludeRecordId, recordContextProperties.getMaxQuery());
            // LinkedHashMap：插入序 = 时间倒序；同 title 首现即最新，后续重复跳过
            Map<String, RecordProcessorProto.RecentHint> dedup = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                if (dedup.size() >= recordContextProperties.getMaxHints()) {
                    break;
                }
                String title = row.get("title") == null ? null : String.valueOf(row.get("title"));
                if (title == null || title.isBlank() || dedup.containsKey(title)) {
                    continue; // 标题缺失 / 同 title 去重（保留最近一条）
                }
                List<String> keywords = parseKeywords(row.get("keywordsJson"));
                if (keywords.size() > recordContextProperties.getMaxKeywords()) {
                    keywords = keywords.subList(0, recordContextProperties.getMaxKeywords());
                }
                String date = row.get("date") == null ? "" : String.valueOf(row.get("date"));
                dedup.put(title, RecordProcessorProto.RecentHint.newBuilder()
                        .setDate(date)
                        .setTitle(title)
                        .addAllKeywords(keywords)
                        .build());
            }
            return new ArrayList<>(dedup.values());
        } catch (Exception e) {
            log.warn("recent_context 组装失败（按空清单继续），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 chunk metadata.keywords（JSONB → List&lt;String&gt;，写法参考 MirrorServiceImpl.parseStringList）
     *
     * <p>JDBC 可能返回 PGobject 或已解析 List；解析失败返回空清单（不抛）。</p>
     */
    private static List<String> parseKeywords(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            if (json instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 工具计划（toolcalling-vault-design.md 第 1/6 节方案 A）
     *
     * <p>Python PlanTools RPC：LLM 按 JSON 约定输出 ≤2 步工具调用计划。
     * 超时默认 3s（设计稿：规划必须快，失败直接跳过走 RAG——绝不拖慢对话）。
     * Python 未上线时抛 UNAVAILABLE，调用方（ToolOrchestrator）降级返回空计划。</p>
     *
     * @param userId     用户 ID（llm_config 在此补齐）
     * @param request    已组装请求（question/glossary/tools）
     * @param timeoutMs  规划超时毫秒（设计稿 3s）
     * @return 计划（可能为空 = 不用工具）
     */
    public MirrorChatProto.PlanToolsReply planTools(UUID userId,
                                                    MirrorChatProto.PlanToolsRequest request,
                                                    long timeoutMs) {
        log.info("gRPC 调用 PlanTools，用户: {}, 问题: {}", userId, request.getQuestion());
        try {
            MirrorChatProto.PlanToolsRequest enriched = request.toBuilder()
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            MirrorChatProto.PlanToolsReply response = chatStub
                    .withDeadlineAfter(Math.max(timeoutMs, 100), TimeUnit.MILLISECONDS)
                    .planTools(enriched);
            log.info("PlanTools 返回: {} 步计划", response.getCallsCount());
            return response;
        } catch (StatusRuntimeException e) {
            log.info("gRPC PlanTools 调用失败（调用方降级走 RAG）: status={}, 用户: {}", e.getStatus(), userId);
            throw e;
        }
    }

    /**
     * 单步规划（对话 Agent 循环，chat-loop-design.md §3/§4.1）
     *
     * <p>服务端流式：Python 先逐块吐 {@code thinking} 增量（B 侧透传 SSE thinking，
     * 消除"黑屏 20s"），最后吐一个 {@code final=true} 的终帧带 {@code calls}/{@code done}。
     * 循环状态（步数/已执行结果/预算）全在 Java，Python 每次只做单步决策（无状态铁律）。</p>
     *
     * <p>deadline = 传入的 {@code timeoutMs}，即 {@code vault.plan-tools-timeout-ms}
     * 的<b>单步</b>语义（§4.3：必须 &lt; mirror.sse-timeout-ms）。整轮循环的累计预算
     * 由调用方（ToolOrchestrator）用 {@code vault.loop-budget-ms} 自己掐表，不在这里管。</p>
     *
     * <p>Python 未上线 / 流中断 → 抛 StatusRuntimeException，调用方按终止条件 6
     * 不重试、带着已有结果退出循环（零回归裁决 0.4）。</p>
     *
     * @param userId    用户 ID（llm_config 在此补齐）
     * @param request   已组装请求（question/glossary/tools/history/previous_results/step/max_steps/has_retrieval）
     * @param timeoutMs 规划<b>单步</b>超时毫秒
     * @return PlanStepChunk 流迭代器（逐块消费；final=true 的块才可读 calls/done）
     */
    public Iterator<MirrorChatProto.PlanStepChunk> planNextStep(UUID userId,
                                                                MirrorChatProto.PlanNextStepRequest request,
                                                                long timeoutMs) {
        log.info("gRPC 调用 PlanNextStep(stream)，用户: {}, 第 {}/{} 步, 已有结果 {} 条, 检索命中: {}",
                userId, request.getStep(), request.getMaxSteps(),
                request.getPreviousResultsCount(), request.getHasRetrieval());
        try {
            // llm_config 注入口径与 planTools 完全一致（用户的 provider/model/key 由 B 侧统一补齐）
            MirrorChatProto.PlanNextStepRequest enriched = request.toBuilder()
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            return chatStub
                    .withDeadlineAfter(Math.max(timeoutMs, 100), TimeUnit.MILLISECONDS)
                    .planNextStep(enriched);
        } catch (StatusRuntimeException e) {
            log.info("gRPC PlanNextStep 调用失败（调用方带着已有结果退出循环）: status={}, 用户: {}",
                    e.getStatus(), userId);
            throw e;
        }
    }

    /**
     * 词条候选抽取（个人词典，lexicon-design.md 第 3 节）
     *
     * <p>Python 侧 ExtractTerms RPC（第 7 套 prompt）由 AI Agent 本轮实现；
     * 未上线时抛 StatusRuntimeException(UNAVAILABLE)，调用方（GlossaryService）打日志跳过，
     * 绝不阻断每日总结/月度画像主流程。超时 180s（14 天语料 + LLM 归纳，取 classify 同级）。</p>
     *
     * @param userId  用户 ID（读取 LLM 配置）
     * @param request 已组装的 ExtractTermsRequest（chunks / existing_terms；llm_config 在此补齐）
     * @return 候选列表（new / evidence / update）
     */
    public RecordProcessorProto.ExtractTermsReply extractTerms(
            UUID userId, RecordProcessorProto.ExtractTermsRequest request) {
        log.info("gRPC 调用 ExtractTerms，用户: {}, 语料: {} 条, 现有词条: {} 条",
                userId, request.getChunksCount(), request.getExistingTermsCount());
        try {
            RecordProcessorProto.ExtractTermsRequest enriched = request.toBuilder()
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            RecordProcessorProto.ExtractTermsReply response = recordStub
                    .withDeadlineAfter(180, TimeUnit.SECONDS)
                    .extractTerms(enriched);
            log.info("ExtractTerms 返回: {} 条候选", response.getCandidatesCount());
            return response;
        } catch (StatusRuntimeException e) {
            log.warn("gRPC ExtractTerms 调用失败: status={}, 用户: {}（调用方负责降级跳过）",
                    e.getStatus(), userId);
            throw e;
        }
    }

    /**
     * 用户是否已配置 LLM（定时抽取/月度维护的幂等跳过判据，不触发真实调用）
     */
    public boolean hasLlmConfig(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        return settings != null && settings.getAiApiKey() != null && !settings.getAiApiKey().isBlank();
    }

    /**
     * 暴露 LlmConfig 构建（GlossaryService 组装 ExtractTermsRequest 时随请求携带）
     */
    public CommonProto.LlmConfig buildLlmConfigFor(UUID userId) {
        return buildLlmConfig(userId);
    }

    /**
     * 从 user_settings 构建 LlmConfig
     */
    private CommonProto.LlmConfig buildLlmConfig(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>()
                        .eq(UserSettings::getUserId, userId)
        );

        CommonProto.LlmConfig.Builder builder = CommonProto.LlmConfig.newBuilder();

        if (settings != null) {
            if (settings.getAiProvider() != null) {
                builder.setProvider(settings.getAiProvider());
            }
            if (settings.getAiProtocol() != null) {
                builder.setProtocol(settings.getAiProtocol().equals("anthropic")
                        ? CommonProto.AiProtocol.ANTHROPIC
                        : CommonProto.AiProtocol.OPENAI);
            }
            if (settings.getAiApiKey() != null) {
                builder.setApiKey(CryptoUtils.decrypt(settings.getAiApiKey()));
            }
            if (settings.getAiBaseUrl() != null) {
                builder.setBaseUrl(settings.getAiBaseUrl());
            }
            if (settings.getAiModel() != null) {
                builder.setModel(settings.getAiModel());
            }
        }

        // 不再允许"缺配置就发请求"：Python 侧已移除厂商兜底模型（qwen-plus 等），
        // 这里同步 fail-fast，避免未配置时被下游静默换成另一个模型真实调用并计费。
        if (builder.getApiKey().isEmpty() || builder.getBaseUrl().isEmpty() || builder.getModel().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "AI 模型未配置完整（API Key / API 地址 / 模型名称），请到设置页填写后再试");
        }

        // 调用审计：谁、用哪个模型、打到哪个地址（用于追查异常账单）
        log.info("[AI调用] userId={} llm model={} baseUrl={} protocol={}",
                userId, builder.getModel(), builder.getBaseUrl(), builder.getProtocol());

        return builder.build();
    }

    /**
     * 从 user_settings 构建 EmbeddingConfig
     */
    private CommonProto.EmbeddingConfig buildEmbeddingConfig(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>()
                        .eq(UserSettings::getUserId, userId)
        );

        CommonProto.EmbeddingConfig.Builder builder = CommonProto.EmbeddingConfig.newBuilder();

        if (settings != null) {
            if (settings.getEmbeddingSource() != null) {
                builder.setSource(settings.getEmbeddingSource());
            }
            if (settings.getEmbeddingModel() != null) {
                builder.setLocalModel(settings.getEmbeddingModel());
                builder.setApiModel(settings.getEmbeddingModel());
            }
            if (settings.getAiProvider() != null) {
                builder.setApiProvider(settings.getAiProvider());
            }
            if (settings.getEmbeddingApiKey() != null) {
                builder.setApiKey(CryptoUtils.decrypt(settings.getEmbeddingApiKey()));
            }
            if (settings.getEmbeddingBaseUrl() != null) {
                builder.setBaseUrl(settings.getEmbeddingBaseUrl());
            }
        }

        // api 来源必须配全（Python 侧已移除厂商兜底 embedding 模型）；local 走本地 BGE，无需这三项
        if ("api".equalsIgnoreCase(builder.getSource())) {
            if (builder.getApiKey().isEmpty() || builder.getBaseUrl().isEmpty() || builder.getApiModel().isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "Embedding 未配置完整（API Key / API 地址 / 模型名称），请到设置页填写后再试");
            }
            log.info("[AI调用] userId={} embedding model={} baseUrl={}", userId, builder.getApiModel(), builder.getBaseUrl());
        } else {
            log.info("[AI调用] userId={} embedding source=local model={}", userId, builder.getLocalModel());
        }

        return builder.build();
    }
}
