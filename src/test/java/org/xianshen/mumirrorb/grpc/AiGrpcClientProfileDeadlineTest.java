package org.xianshen.mumirrorb.grpc;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileGrpc;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import org.springframework.context.ApplicationContext;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiGrpcClientProfileDeadlineTest {

    @Test
    void profileCallAllowsAtLeastNinetySecondsForModelResponse() throws Exception {
        AtomicLong remainingDeadlineMillis = new AtomicLong(-1);
        Server server = ServerBuilder.forPort(0)
                .addService(new MirrorProfileGrpc.MirrorProfileImplBase() {
                    @Override
                    public void generateProfile(MirrorProfileProto.GenerateProfileRequest request,
                                                StreamObserver<MirrorProfileProto.GenerateProfileResponse> observer) {
                        remainingDeadlineMillis.set(Context.current().getDeadline()
                                .timeRemaining(TimeUnit.MILLISECONDS));
                        observer.onNext(MirrorProfileProto.GenerateProfileResponse.newBuilder().build());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext()
                .build();

        try {
            SettingsMapper settingsMapper = mock(SettingsMapper.class);
            UserSettings settings = new UserSettings();
            settings.setAiProvider("test");
            settings.setAiProtocol("openai");
            settings.setAiApiKey(CryptoUtils.encrypt("test-key"));
            settings.setAiBaseUrl("https://example.invalid");
            settings.setAiModel("test-model");
            when(settingsMapper.selectOne(any())).thenReturn(settings);

            ApplicationContext applicationContext = mock(ApplicationContext.class);
            when(applicationContext.getBean(TodoRegistryService.class))
                    .thenReturn(mock(TodoRegistryService.class));

            AiGrpcClient client = new AiGrpcClient(
                    channel,
                    settingsMapper,
                    mock(GlossaryService.class),
                    applicationContext,
                    mock(ChunkMapper.class),
                    new RecordContextProperties());
            client.init();

            client.generateProfile(UUID.randomUUID(), MirrorProfileProto.GenerateProfileRequest.newBuilder().build());

            assertTrue(remainingDeadlineMillis.get() >= TimeUnit.SECONDS.toMillis(90),
                    "profile RPC deadline should leave room beyond the old 60-second cutoff");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
