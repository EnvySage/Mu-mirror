package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
class UsageGrpcDatabaseTest extends UsageDatabaseFixture {
    @Test void grpcUnaryAndStreamsCarryTrustedOwnershipAndPreserveMissingReceipts() throws Exception {
        var interceptor=new UsageClientInterceptor(store,new ObjectMapper());
        var invocationKey=io.grpc.Metadata.Key.of("mirror-usage-invocation-id",io.grpc.Metadata.ASCII_STRING_MARSHALLER);
        var taskKey=io.grpc.Metadata.Key.of("mirror-usage-task-id",io.grpc.Metadata.ASCII_STRING_MARSHALLER);
        List<String> observedTasks=new ArrayList<>();
        io.grpc.ServerInterceptor trailers=new io.grpc.ServerInterceptor() {
            public <Q,A> io.grpc.ServerCall.Listener<Q> interceptCall(io.grpc.ServerCall<Q,A> call,io.grpc.Metadata headers,io.grpc.ServerCallHandler<Q,A> next) {
                String invocation=headers.get(invocationKey);observedTasks.add(headers.get(taskKey));
                return next.startCall(new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    public void close(io.grpc.Status status,io.grpc.Metadata metadata) {
                        if(invocation!=null&&status.isOk()) metadata.put(UsageClientInterceptor.REPORT,
                            ("{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":true,\"attempts\":[{\"id\":\""+UUID.randomUUID()+"\",\"status\":\"success\",\"provider\":\"deepseek\",\"model\":\"deepseek-v4.1-flash\",\"total_tokens\":50,\"usage_state\":\"complete\"}]}").getBytes(StandardCharsets.UTF_8));
                        super.close(status,metadata);
                    }
                },headers);
            }
        };
        var record=new org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc.RecordProcessorImplBase() {
            public void classify(org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyRequest request,io.grpc.stub.StreamObserver<org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse> out) {
                out.onNext(org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse.getDefaultInstance());out.onCompleted();
            }
        };
        var chat=new org.xianshen.mumirrorb.grpc.gen.MirrorChatGrpc.MirrorChatImplBase() {
            public void chat(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatRequest request,io.grpc.stub.StreamObserver<org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatChunk> out) {
                out.onNext(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatChunk.newBuilder().setContent("answer").build());out.onCompleted();
            }
        };
        var server=io.grpc.ServerBuilder.forPort(0).addService(io.grpc.ServerInterceptors.intercept(record,trailers))
                .addService(io.grpc.ServerInterceptors.intercept(chat,trailers)).build().start();
        var channel=io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().maxInboundMetadataSize(65536).build();
        try {
            var config=org.xianshen.mumirrorb.grpc.gen.CommonProto.LlmConfig.newBuilder().setModel("deepseek-v4.1-flash")
                    .setProvider("deepseek").setBaseUrl("https://user:secret@example.com/v1?api_key=secret").setApiKey("never-persist-this").build();
            var sameValueConfig=config.toBuilder().build();assertNotSame(config,sameValueConfig);assertEquals(config,sameValueConfig);
            UUID profileA=UUID.randomUUID(),profileB=UUID.randomUUID();
            db.update("insert into user_model_profiles(id,user_id,name) values(?,?,'first'),(?,?,'second')",profileA,user,profileB,user);
            db.update("insert into user_settings values(?,?,?,?,?)",user,profileA,config.getBaseUrl(),config.getModel(),config.getProvider());
            interceptor.remember(user,config,profileA);interceptor.remember(user,sameValueConfig,profileB);
            db.update("update user_settings set active_model_profile_id=? where user_id=?",profileB,user);
            db.update("delete from user_model_profiles where id=?",profileA);
            UUID task=UUID.randomUUID();
            try(var scope=UsageTaskScope.open(user,task,"chat")) {
                var app=org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class);
                org.mockito.Mockito.when(app.getBean(org.xianshen.mumirrorb.service.TodoRegistryService.class))
                    .thenReturn(org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.TodoRegistryService.class));
                var client=new org.xianshen.mumirrorb.grpc.AiGrpcClient(channel,
                    org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.SettingsMapper.class),
                    org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.GlossaryService.class),app,
                    org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.ChunkMapper.class),new org.xianshen.mumirrorb.config.RecordContextProperties());
                org.springframework.test.util.ReflectionTestUtils.setField(client,"usageInterceptor",interceptor);client.init();
                client.testLlmConnection(user,config);
                var stream=org.xianshen.mumirrorb.grpc.gen.MirrorChatGrpc.newBlockingStub(channel).withInterceptors(interceptor)
                    .withOption(UsageClientInterceptor.OWNER,user).chat(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatRequest.newBuilder().setLlmConfig(sameValueConfig).build());
                assertEquals("answer",stream.next().getContent());assertFalse(stream.hasNext());
            }
            store.finishTask(user,task);
            assertEquals(List.of(task.toString(),task.toString()),observedTasks);
            var detail=queries.detail(user,task);
            assertEquals(100L,((Number)detail.get("totalTokens")).longValue());
            assertEquals("complete",detail.get("status"));
            assertEquals(List.of(profileA,profileB),db.queryForList("select profile_id from ai_usage_invocations where user_id=? order by started_at",UUID.class,user));
            assertEquals(2,db.queryForObject("select count(distinct channel_key) from ai_usage_invocations where user_id=?",Integer.class,user).intValue());
            String persisted=db.queryForObject("SELECT row_to_json(i)::text FROM ai_usage_invocations i WHERE task_id=? LIMIT 1",String.class,task);
            assertFalse(persisted.contains("never-persist-this"));assertFalse(persisted.contains("secret"));
        } finally {channel.shutdownNow().awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);server.shutdownNow().awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}
    }

    @Test void everyClientEntryBindsOwnerSceneConfigAndExcludesEmbeddingEvenOnWorkers() throws Exception {
        List<String> scenes=new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> excluded=new java.util.concurrent.CopyOnWriteArrayList<>();
        io.grpc.ServerInterceptor trailers=new io.grpc.ServerInterceptor() {
            public <Q,A> io.grpc.ServerCall.Listener<Q> interceptCall(io.grpc.ServerCall<Q,A> call,io.grpc.Metadata headers,io.grpc.ServerCallHandler<Q,A> next) {
                String invocation=headers.get(io.grpc.Metadata.Key.of("mirror-usage-invocation-id",io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                String scene=headers.get(io.grpc.Metadata.Key.of("mirror-usage-scene",io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                if(call.getMethodDescriptor().getFullMethodName().contains("EmbeddingService/")) {
                    assertNull(invocation);excluded.add(call.getMethodDescriptor().getBareMethodName());
                } else {assertNotNull(invocation);scenes.add(scene);}
                return next.startCall(new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    public void close(io.grpc.Status status,io.grpc.Metadata metadata) {
                        if(invocation!=null)metadata.put(UsageClientInterceptor.REPORT,("{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":true,\"attempts\":[]}").getBytes(StandardCharsets.UTF_8));
                        super.close(status,metadata);
                    }
                },headers);
            }
        };
        var builder=io.grpc.ServerBuilder.forPort(0);
        for(var descriptor:List.of(org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc.getServiceDescriptor(),
                org.xianshen.mumirrorb.grpc.gen.MirrorChatGrpc.getServiceDescriptor(),org.xianshen.mumirrorb.grpc.gen.MirrorProfileGrpc.getServiceDescriptor(),
                org.xianshen.mumirrorb.grpc.gen.LedgerVisionGrpc.getServiceDescriptor(),org.xianshen.mumirrorb.grpc.gen.TarotReadingGrpc.getServiceDescriptor(),
                org.xianshen.mumirrorb.grpc.gen.AgentRunnerGrpc.getServiceDescriptor(),org.xianshen.mumirrorb.grpc.gen.EmbeddingServiceGrpc.getServiceDescriptor()))
            builder.addService(io.grpc.ServerInterceptors.intercept(defaultService(descriptor),trailers));
        var server=builder.build().start();
        var channel=io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().maxInboundMetadataSize(96*1024).build();
        try {
            var settings=org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.SettingsMapper.class);
            org.mockito.Mockito.when(settings.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(
                org.xianshen.mumirrorb.pojo.DO.UserSettings.builder().aiProvider("deepseek").aiModel("deepseek-v4.1-flash")
                    .aiBaseUrl("https://example.com/v1").aiApiKey(org.xianshen.mumirrorb.common.utils.CryptoUtils.encrypt("fixture-key")).build());
            var app=org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class);
            org.mockito.Mockito.when(app.getBean(org.xianshen.mumirrorb.service.TodoRegistryService.class)).thenReturn(org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.TodoRegistryService.class));
            var client=new org.xianshen.mumirrorb.grpc.AiGrpcClient(channel,settings,
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.GlossaryService.class),app,
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.ChunkMapper.class),new org.xianshen.mumirrorb.config.RecordContextProperties());
            org.springframework.test.util.ReflectionTestUtils.setField(client,"usageInterceptor",new UsageClientInterceptor(store,new ObjectMapper()));client.init();
            // No SecurityContext is installed: all these methods also run on background workers.
            client.testLlmConnection(user);client.classify(user,"fixture");client.classifySingle(user,"fixture");
            client.extractTerms(user,org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ExtractTermsRequest.getDefaultInstance());
            client.extractIntent(user,"fixture");
            client.planTools(user,org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.PlanToolsRequest.getDefaultInstance(),10000);
            var planning=client.planNextStep(user,org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.PlanNextStepRequest.getDefaultInstance(),10000);while(planning.hasNext())planning.next();
            client.chatBlocking(user,org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatRequest.getDefaultInstance());
            client.summarizeDay(user,LocalDate.of(2026,11,14),"fixture",List.of(),"Asia/Shanghai");
            client.generateProfile(user,org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto.GenerateProfileRequest.getDefaultInstance());
            client.recognizeLedgerReceipt(user,new byte[]{1},"image/png",LocalDate.of(2026,11,14));
            new org.xianshen.mumirrorb.grpc.TarotGrpcClient(channel,client).analyze(user,org.xianshen.mumirrorb.grpc.gen.TarotProto.TarotAnalyzeRequest.getDefaultInstance());
            client.embed(user,"fixture");client.getModelInfo(user);
            var agent=new org.xianshen.mumirrorb.grpc.AgentGrpcClient(new org.xianshen.mumirrorb.config.AgentProperties(),client,
                    org.mockito.Mockito.mock(org.xianshen.mumirrorb.tools.ToolRegistry.class));
            org.springframework.test.util.ReflectionTestUtils.setField(agent,"channel",channel);
            List<UUID> graphTasks=new ArrayList<>();
            for(boolean shadow:List.of(false,true)) {
                UUID run=UUID.randomUUID();graphTasks.add(run);var done=new java.util.concurrent.CountDownLatch(1);
                agent.run(org.xianshen.mumirrorb.grpc.gen.AgentProto.AgentRunRequest.newBuilder().setRunId(run.toString()).setUserId(user.toString())
                    .setLlmConfig(client.buildLlmConfigFor(user)).setShadow(shadow).build(),new io.grpc.stub.StreamObserver<org.xianshen.mumirrorb.grpc.gen.AgentProto.AgentEvent>() {
                        public void onNext(org.xianshen.mumirrorb.grpc.gen.AgentProto.AgentEvent event) { }
                        public void onError(Throwable error) {done.countDown();fail(error);}
                        public void onCompleted() {done.countDown();}
                    });
                assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(List.of("model_test","record_analysis","record_analysis","glossary","intent","planning","planning","summary","chat_memory","profile","receipt","tarot","chat","shadow"),scenes);
            assertEquals(List.of("Embed","GetModelInfo"),excluded);
            assertEquals(14L,db.queryForObject("SELECT count(*) FROM ai_usage_invocations WHERE user_id=?",Long.class,user));
            assertEquals("chat",queries.detail(user,graphTasks.get(0)).get("scene"));
            assertEquals("shadow",queries.detail(user,graphTasks.get(1)).get("scene"));
            assertEquals("deepseek-v4.1-flash",queries.detail(user,graphTasks.get(1)).get("model"));
        } finally {channel.shutdownNow().awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);server.shutdownNow().awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static io.grpc.ServerServiceDefinition defaultService(io.grpc.ServiceDescriptor descriptor) {
        var service=io.grpc.ServerServiceDefinition.builder(descriptor);
        for(io.grpc.MethodDescriptor method:descriptor.getMethods()) {
            io.grpc.stub.ServerCalls.UnaryMethod handler=(request,out)->{
                Object response=method.getResponseMarshaller().parse(new java.io.ByteArrayInputStream(new byte[0]));
                out.onNext(response);out.onCompleted();
            };
            if(method.getType()==io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
                service.addMethod(method,io.grpc.stub.ServerCalls.asyncServerStreamingCall((request,out)->handler.invoke(request,out)));
            else service.addMethod(method,io.grpc.stub.ServerCalls.asyncUnaryCall(handler));
        }
        return service.build();
    }
}

