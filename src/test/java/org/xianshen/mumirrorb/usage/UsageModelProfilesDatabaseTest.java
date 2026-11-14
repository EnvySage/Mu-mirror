package org.xianshen.mumirrorb.usage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorGrpc;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ModelConnectionTestDTO;
import org.xianshen.mumirrorb.service.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
class UsageModelProfilesDatabaseTest extends UsageDatabaseFixture {
    Server server;ManagedChannel channel;AiGrpcClient ai;ModelProfileService profiles;
    SettingsMapper settings;AtomicInteger calls;
    @BeforeEach void modelService() throws Exception {
        db.execute("DROP TABLE user_model_profiles");
        String ddl=new ClassPathResource("db/migration-v15-model-profiles.sql").getContentAsString(StandardCharsets.UTF_8);
        var table=Pattern.compile("CREATE TABLE IF NOT EXISTS user_model_profiles \\([\\s\\S]*?\\n\\);").matcher(ddl);
        assertTrue(table.find());db.execute(table.group());
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
        configuration.setEnvironment(new Environment("usage-profiles",new SpringManagedTransactionFactory(),db.getDataSource()));
        configuration.addMapper(ModelProfileMapper.class);
        var mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(ModelProfileMapper.class);
        calls=new AtomicInteger();
        server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new RecordProcessorGrpc.RecordProcessorImplBase() {
            public void classify(RecordProcessorProto.ClassifyRequest request,StreamObserver<RecordProcessorProto.ClassifyResponse> out) {
                calls.incrementAndGet();out.onNext(RecordProcessorProto.ClassifyResponse.newBuilder()
                        .addItems(RecordProcessorProto.ClassifyItem.newBuilder().setTitle("fixture")).build());out.onCompleted();
            }
        },new ServerInterceptor() {
            public <Q,A> ServerCall.Listener<Q> interceptCall(ServerCall<Q,A> call,Metadata headers,ServerCallHandler<Q,A> next) {
                String invocation=headers.get(Metadata.Key.of("mirror-usage-invocation-id",Metadata.ASCII_STRING_MARSHALLER));
                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    public void close(Status status,Metadata metadata) {
                        metadata.put(UsageClientInterceptor.REPORT,("{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":true,\"attempts\":[]}").getBytes(StandardCharsets.UTF_8));super.close(status,metadata);
                    }
                },headers);
            }
        })).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().maxInboundMetadataSize(96*1024).build();
        settings=mock(SettingsMapper.class);var context=mock(ApplicationContext.class);
        when(context.getBean(TodoRegistryService.class)).thenReturn(mock(TodoRegistryService.class));
        ai=new AiGrpcClient(channel,settings,mock(GlossaryService.class),context,mock(ChunkMapper.class),new RecordContextProperties());
        ReflectionTestUtils.setField(ai,"usageInterceptor",new UsageClientInterceptor(store,new ObjectMapper()));ai.init();
        profiles=new ModelProfileService(mapper,settings,ai);
    }
    @AfterEach void closeServer() throws Exception {
        if(channel!=null)channel.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);
    }
    UUID saved(UUID owner,String name) {
        UUID id=UUID.randomUUID();db.update("""
            INSERT INTO user_model_profiles(id,user_id,name,ai_provider,ai_protocol,ai_base_url,ai_model,ai_api_key)
            VALUES(?,?,?,'deepseek','openai','https://example.com/v1','deepseek-v4.1-flash',?)
            """,id,owner,name,CryptoUtils.encrypt("fixture-key"));return id;
    }
    ModelConnectionTestDTO draft(UUID profile) {
        var dto=new ModelConnectionTestDTO();dto.setProfileId(profile);dto.setAiProvider("deepseek");dto.setAiProtocol("openai");
        dto.setAiBaseUrl("https://example.com/v1");dto.setAiModel("deepseek-v4.1-flash");return dto;
    }
    @Test void equalSavedConnectionsKeepDistinctDraftSnapshotsAndNormalProbeKeepsActiveProfile() {
        UUID a=saved(user,"profile-a"),b=saved(user,"profile-b");
        profiles.testDraft(user,draft(a));profiles.testDraft(user,draft(b));
        var unsaved=draft(null);unsaved.setAiApiKey("fixture-key");profiles.testDraft(user,unsaved);
        when(settings.selectOne(any())).thenReturn(UserSettings.builder().userId(user).activeModelProfileId(a)
                .aiProvider("deepseek").aiProtocol("openai").aiBaseUrl("https://example.com/v1").aiModel("deepseek-v4.1-flash")
                .aiApiKey(CryptoUtils.encrypt("fixture-key")).build());
        ai.testLlmConnection(user);
        assertEquals(Arrays.asList(a,b,null,a),db.queryForList("SELECT profile_id FROM ai_usage_invocations WHERE user_id=? ORDER BY started_at",UUID.class,user));
        var keys=db.queryForList("SELECT channel_key FROM ai_usage_invocations WHERE user_id=? ORDER BY started_at",String.class,user);
        assertEquals(3,new HashSet<>(keys).size());assertEquals(keys.get(0),keys.get(3));assertEquals(4,calls.get());
    }
    @Test void unownedSavedProfileIsRefusedBeforeAnyRpcEvenWithReplacementKey() {
        UUID outsider=UUID.randomUUID();db.update("INSERT INTO users(id) VALUES(?)",outsider);
        var dto=draft(saved(outsider,"not-owned"));dto.setAiApiKey("replacement-fixture");
        assertEquals(404,assertThrows(BusinessException.class,()->profiles.testDraft(user,dto)).getCode());
        assertEquals(0,calls.get());assertEquals(0L,db.queryForObject("SELECT count(*) FROM ai_usage_invocations WHERE user_id=?",Long.class,user));
    }
}
