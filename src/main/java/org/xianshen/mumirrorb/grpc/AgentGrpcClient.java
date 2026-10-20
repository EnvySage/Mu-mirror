package org.xianshen.mumirrorb.grpc;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.agent.ChatInvocation;
import org.xianshen.mumirrorb.agent.AgentWriteGrantService;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.grpc.gen.AgentRunnerGrpc;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Separate channel to Python's 10004 Agent service; never calls legacy query RPCs. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGrpcClient {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentProperties properties;
    private final AiGrpcClient aiGrpcClient;
    private final AgentWriteGrantService writeGrantService;
    private ManagedChannel channel;

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forTarget(properties.getGrpcAddress())
                .usePlaintext()
                .keepAliveTime(60, TimeUnit.SECONDS)
                .build();
        log.info("Agent gRPC channel configured: {}", properties.getGrpcAddress());
    }

    public AgentProto.AgentRunRequest buildRequest(ChatInvocation invocation, AgentRun run) {
        CommonProto.LlmConfig llm = aiGrpcClient.buildLlmConfigFor(invocation.userId());
        CommonProto.EmbeddingConfig embedding = aiGrpcClient.buildEmbeddingConfigFor(invocation.userId());
        AgentProto.AgentRunRequest.Builder request = AgentProto.AgentRunRequest.newBuilder()
                .setRunId(invocation.runId().toString())
                .setUserId(invocation.userId().toString())
                .setSessionId(invocation.sessionId().toString())
                .setUserMessageId(invocation.userMessageId())
                .setQuestion(invocation.question())
                .setLlmConfig(llm)
                .setEmbeddingConfig(embedding)
                .setReferenceDate(LocalDate.now(ZONE).toString())
                .setTimezone(ZONE.getId())
                .setMaxPlanRounds(properties.getMaxPlanRounds())
                .setTotalBudgetMs(properties.getTotalBudgetMs())
                .setLoopBudgetMs(properties.getLoopBudgetMs())
                .setHalfLifeDays(30.0);
        writeGrantService.authorizeCreate(invocation.question(), run).ifPresent(grant -> request
                .setCommandGrant(grant.token())
                .setAuthorizedRecordContent(grant.authorizedContent())
                .addAllowedWriteTools("create_record"));
        return request.build();
    }

    public CancellableCall run(AgentProto.AgentRunRequest request,
                               StreamObserver<AgentProto.AgentEvent> responseObserver) {
        if (channel == null || channel.isShutdown()) {
            throw new IllegalStateException("Agent gRPC channel is unavailable");
        }
        Metadata headers = new Metadata();
        String token = properties.getInternalToken();
        if (token != null && !token.isBlank()) {
            headers.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        }
        AgentRunnerGrpc.AgentRunnerStub stub = AgentRunnerGrpc.newStub(
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers)))
                .withDeadlineAfter(properties.getTotalBudgetMs() + 10_000, TimeUnit.MILLISECONDS);

        AtomicReference<ClientCallStreamObserver<AgentProto.AgentRunRequest>> callRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ClientResponseObserver<AgentProto.AgentRunRequest, AgentProto.AgentEvent> observer =
                new ClientResponseObserver<>() {
                    @Override
                    public void beforeStart(ClientCallStreamObserver<AgentProto.AgentRunRequest> requestStream) {
                        callRef.set(requestStream);
                        if (cancelled.get()) {
                            requestStream.cancel("chat run cancelled", null);
                        }
                    }

                    @Override
                    public void onNext(AgentProto.AgentEvent value) {
                        responseObserver.onNext(value);
                    }

                    @Override
                    public void onError(Throwable error) {
                        responseObserver.onError(error);
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onCompleted();
                    }
                };
        stub.runAgent(request, observer);
        return () -> {
            cancelled.set(true);
            ClientCallStreamObserver<AgentProto.AgentRunRequest> call = callRef.get();
            if (call != null) {
                call.cancel("chat run cancelled", null);
            }
        };
    }

    @PreDestroy
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    public interface CancellableCall {
        void cancel();
    }
}
