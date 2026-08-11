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
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;

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

    @PostConstruct
    public void init() {
        recordStub = RecordProcessorGrpc.newBlockingStub(channel);
        embedStub = EmbeddingServiceGrpc.newBlockingStub(channel);
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

            RecordProcessorProto.ClassifyRequest request = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
                    .setLlmConfig(llmConfig)
                    .build();

            RecordProcessorProto.ClassifyResponse response = recordStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .classify(request);

            log.info("Classify 返回: title={}, contentType={}, skip={}",
                    response.getTitle(), response.getContentType(), response.getSkip());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 调用失败: {}", e.getStatus(), e);
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
        }

        return builder.build();
    }
}
