package org.xianshen.mumirrorb.grpc;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGrpcClientDraftConnectionTest {
    @Test void explicitDraftTravelsOverGrpcWithoutReadingTheActiveSettings() throws Exception {
        var observed = new AtomicReference<RecordProcessorProto.ClassifyRequest>();
        Server server = ServerBuilder.forPort(0).addService(new RecordProcessorGrpc.RecordProcessorImplBase() {
            @Override public void classify(RecordProcessorProto.ClassifyRequest request,
                    StreamObserver<RecordProcessorProto.ClassifyResponse> observer) {
                observed.set(request);
                observer.onNext(RecordProcessorProto.ClassifyResponse.newBuilder()
                        .addItems(RecordProcessorProto.ClassifyItem.newBuilder().setTitle("ok")).build());
                observer.onCompleted();
            }
        }).build().start();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            var settings = mock(SettingsMapper.class); var app = mock(ApplicationContext.class);
            when(app.getBean(TodoRegistryService.class)).thenReturn(mock(TodoRegistryService.class));
            var client = new AiGrpcClient(channel, settings, mock(GlossaryService.class), app,
                    mock(ChunkMapper.class), new RecordContextProperties()); client.init();
            var draft = CommonProto.LlmConfig.newBuilder().setProvider("draft-provider")
                    .setProtocol(CommonProto.AiProtocol.ANTHROPIC).setBaseUrl("https://draft.test")
                    .setModel("draft-model").setApiKey("draft-secret").build();
            assertEquals(1, client.testLlmConnection(UUID.randomUUID(), draft).getItemsCount());
            assertEquals(draft, observed.get().getLlmConfig()); assertTrue(observed.get().getProcessingAuthorized());
            assertTrue(observed.get().getSingle()); verifyNoInteractions(settings);
        } finally { channel.shutdownNow(); server.shutdownNow(); }
    }
}
