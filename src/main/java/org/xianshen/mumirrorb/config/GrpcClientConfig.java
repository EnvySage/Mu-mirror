package org.xianshen.mumirrorb.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * gRPC 客户端配置
 *
 * 管理到 Python AI 服务的 gRPC 连接
 */
@Slf4j
@Configuration
public class GrpcClientConfig {

    @Value("${grpc.client.ai-service.address:localhost:50051}")
    private String aiServiceAddress;

    @Value("${grpc.client.ai-service.deadline-seconds:30}")
    private int defaultDeadlineSeconds;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel aiServiceChannel() {
        log.info("正在连接 Python AI 服务: {}", aiServiceAddress);
        channel = ManagedChannelBuilder.forTarget(aiServiceAddress)
                .usePlaintext()  // 开发环境不加密，生产环境改 TLS
                .keepAliveTime(60, TimeUnit.SECONDS)
                .build();
        return channel;
    }

    @Bean
    public int defaultDeadline() {
        return defaultDeadlineSeconds;
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            log.info("正在关闭 gRPC 连接...");
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
