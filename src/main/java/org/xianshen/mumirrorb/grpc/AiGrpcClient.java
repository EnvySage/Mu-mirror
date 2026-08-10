package org.xianshen.mumirrorb.grpc;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingServiceGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;

import java.util.concurrent.TimeUnit;

/**
 * AI gRPC 客户端
 *
 * 封装对 Python AI 服务的 gRPC 调用，Java 端只调这个类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiGrpcClient {

    private final ManagedChannel channel;

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
     * @param content 用户输入的文本
     * @return 分类结果（标题、摘要、标签等）
     */
    public RecordProcessorProto.ClassifyResponse classify(String content) {
        log.info("gRPC 调用 Classify，内容长度: {}", content.length());
        try {
            RecordProcessorProto.ClassifyRequest request = RecordProcessorProto.ClassifyRequest.newBuilder()
                    .setContent(content)
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
     * @param text 要向量化的文本
     * @return 向量化结果
     */
    public EmbeddingProto.EmbedResponse embed(String text) {
        log.info("gRPC 调用 Embed，文本长度: {}", text.length());
        try {
            EmbeddingProto.EmbedRequest request = EmbeddingProto.EmbedRequest.newBuilder()
                    .setText(text)
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
     * 查询 Embedding 模型信息
     */
    public EmbeddingProto.ModelInfoResponse getModelInfo() {
        log.info("gRPC 调用 GetModelInfo");
        try {
            return embedStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getModelInfo(EmbeddingProto.ModelInfoRequest.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            log.error("gRPC GetModelInfo 调用失败: {}", e.getStatus(), e);
            throw e;
        }
    }
}
