package org.xianshen.mumirrorb.grpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingServiceGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * AI gRPC 客户端
 *
 * 封装对 Python AI 服务的 gRPC 调用，Java 端只调这个类。
 * 每次调用从 user_settings 读取用户的模型配置，放入请求中传给 Python。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiGrpcClient {

    private final ManagedChannel channel;
    private final SettingsMapper settingsMapper;

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
        log.info("gRPC 调用 Classify，用户: {}, 内容长度: {}", userId, content.length());
        try {
            CommonProto.LlmConfig llmConfig = buildLlmConfig(userId);
            log.debug("Classify LLM 配置: provider={}, model={}, protocol={}",
                    llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getProtocol());

            RecordProcessorProto.ClassifyRequest request = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
                    .setLlmConfig(llmConfig)
                    .build();

            RecordProcessorProto.ClassifyResponse response = recordStub
                    .withDeadlineAfter(180, TimeUnit.SECONDS)
                    .classify(request);

            // 记录拆分结果
            int itemCount = response.getItemsList().size();
            log.info("Classify 返回: skip={}, 拆分 {} 条", response.getSkip(), itemCount);

            if (!response.getSkip()) {
                for (int i = 0; i < response.getItemsList().size(); i++) {
                    RecordProcessorProto.ClassifyItem item = response.getItemsList().get(i);
                    log.info("  ClassifyItem [{}]: title={}, contentType={}, moods={}, keywords={}",
                            i + 1, item.getTitle(), item.getContentType(),
                            item.getMoodsList(), item.getKeywordsList());
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
        log.info("gRPC 调用 Classify(single=true)，用户: {}, 内容长度: {}", userId, content.length());
        try {
            CommonProto.LlmConfig llmConfig = buildLlmConfig(userId);

            RecordProcessorProto.ClassifyRequest request = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
                    .setLlmConfig(llmConfig)
                    .setSingle(true)
                    .build();

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
        log.info("gRPC 调用 Embed，用户: {}, 文本长度: {}", userId, text.length());
        try {
            CommonProto.EmbeddingConfig embedConfig = buildEmbeddingConfig(userId);

            EmbeddingProto.EmbedRequest request = EmbeddingProto.EmbedRequest.newBuilder()
                    .setText(text)
                    .setEmbeddingConfig(embedConfig)
                    .build();

            EmbeddingProto.EmbedResponse response = embedStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
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
            return chatStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
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
     * @param userId 用户 ID（读取 LLM 配置）
     * @param query  用户原始提问
     * @return ExtractIntentResponse（query_type / content_type / moods / time_range / rewritten_query）
     */
    public MirrorChatProto.ExtractIntentResponse extractIntent(UUID userId, String query) {
        log.info("gRPC 调用 ExtractIntent，用户: {}, query: {}", userId, query);
        try {
            MirrorChatProto.ExtractIntentRequest request = MirrorChatProto.ExtractIntentRequest.newBuilder()
                    .setQuery(query)
                    .setLlmConfig(buildLlmConfig(userId))
                    .build();
            return chatStub
                    .withDeadlineAfter(15, TimeUnit.SECONDS)
                    .extractIntent(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC ExtractIntent 调用失败: {}", e.getStatus(), e);
            throw e;
        }
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

        return builder.build();
    }
}
