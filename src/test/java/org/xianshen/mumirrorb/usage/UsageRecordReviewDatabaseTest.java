package org.xianshen.mumirrorb.usage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.*;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.service.*;
import org.xianshen.mumirrorb.service.impl.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real review SQL + gRPC receipts; only embedding workers and unrelated tool effects are stubbed. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
class UsageRecordReviewDatabaseTest extends UsageDatabaseFixture {
    Server server;ManagedChannel channel;
    RecordServiceImpl records;TodoRegistryService todos;
    long record;List<Long> chunks;
    @BeforeEach void reviewServices() throws Exception {
        String ddl=new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("records","chunks")) executeFragment(ddl,"CREATE TABLE IF NOT EXISTS "+table+" \\([\\s\\S]*?\\n\\);");
        executeFragment(ddl,"ALTER TABLE records\\s+ADD COLUMN IF NOT EXISTS memory_state[\\s\\S]*?;");
        executeFragment(ddl,"ALTER TABLE records ADD COLUMN IF NOT EXISTS growth_suggestions[\\s\\S]*?;");
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
        configuration.setEnvironment(new Environment("usage-review",new SpringManagedTransactionFactory(),db.getDataSource()));
        configuration.addMapper(RecordMapper.class);configuration.addMapper(ChunkMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var rm=session.getMapper(RecordMapper.class);var cm=session.getMapper(ChunkMapper.class);
        server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new RecordProcessorGrpc.RecordProcessorImplBase() {
            @Override public void classify(RecordProcessorProto.ClassifyRequest request,StreamObserver<RecordProcessorProto.ClassifyResponse> out) {
                out.onNext(RecordProcessorProto.ClassifyResponse.newBuilder().addItems(RecordProcessorProto.ClassifyItem.newBuilder()
                        .setContent(request.getContent()).setTitle("fixture").setContentType(CommonProto.ContentType.HEALTH)).build());out.onCompleted();
            }
        },new ServerInterceptor() {
            @Override public <Q,A> ServerCall.Listener<Q> interceptCall(ServerCall<Q,A> call,Metadata headers,ServerCallHandler<Q,A> next) {
                String invocation=headers.get(Metadata.Key.of("mirror-usage-invocation-id",Metadata.ASCII_STRING_MARSHALLER));
                assertNotNull(invocation);
                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override public void close(Status status,Metadata metadata) {
                        metadata.put(UsageClientInterceptor.REPORT,("{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":true,\"attempts\":[{\"id\":\""+UUID.randomUUID()+"\",\"status\":\"success\",\"provider\":\"deepseek\",\"model\":\"deepseek-v4.1-flash\",\"total_tokens\":50,\"usage_state\":\"complete\"}]}").getBytes(StandardCharsets.UTF_8));
                        super.close(status,metadata);
                    }
                },headers);
            }
        })).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().maxInboundMetadataSize(96*1024).build();
        var settings=mock(SettingsMapper.class);
        when(settings.selectOne(any())).thenReturn(UserSettings.builder().aiProvider("deepseek").aiModel("deepseek-v4.1-flash")
                .aiBaseUrl("https://example.com/v1").aiApiKey(CryptoUtils.encrypt("fixture-key")).build());
        var context=mock(ApplicationContext.class);todos=mock(TodoRegistryService.class);when(context.getBean(TodoRegistryService.class)).thenReturn(todos);
        var ai=new AiGrpcClient(channel,settings,mock(GlossaryService.class),context,mock(ChunkMapper.class),new RecordContextProperties());
        ReflectionTestUtils.setField(ai,"usageInterceptor",new UsageClientInterceptor(store,new ObjectMapper()));ai.init();
        records=new RecordServiceImpl(rm,cm,mock(SnapshotLineageService.class),mock(ApplicationEventPublisher.class),ai,context,mock(ChunkEmbeddingJobService.class));
        record=db.queryForObject("INSERT INTO records(user_id,content,status,index_policy_version) VALUES(?,'review fixture','reviewing',1) RETURNING id",Long.class,user);
        chunks=new ArrayList<>();
        for(int i=0;i<2;i++) chunks.add(db.queryForObject("INSERT INTO chunks(user_id,record_id,content,segment,embedding_enabled,metadata) VALUES(?,?,'fixture',?,false,'{}') RETURNING id",Long.class,user,record,"part "+i));
    }
    void executeFragment(String ddl,String regex) {
        var match=Pattern.compile(regex).matcher(ddl);assertTrue(match.find(),regex);db.execute(match.group());
    }
    @AfterEach void closeServer() throws Exception {
        if(channel!=null)channel.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);
        // Tables belong only to this class's random fixture schema, never public or a live database.
        db.execute("DROP TABLE IF EXISTS chunks,records CASCADE");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void twoApprovedUnclassifiedChunksShareOneTaskAndReceiptsOutliveBusinessRollback(boolean rollback) {
        if(rollback)doThrow(new BusinessException(409,"fixture conflict after model work"))
                .when(todos).applyRecordResolutions(eq(record),eq(user),any());
        Runnable confirm=()->tx.executeWithoutResult(status->records.confirmReview(record,user,chunks,List.of(),List.of()));
        if(rollback)assertThrows(BusinessException.class,confirm::run);else confirm.run();
        var page=queries.tasks(user,30,null,null,null,null,null,null);
        var tasks=(List<Map<String,Object>>)page.get("items");
        assertEquals(1,tasks.size(),"one record confirmation must not bubble once per classified chunk");
        var task=tasks.get(0);assertEquals("record_analysis",task.get("scene"));assertEquals("complete",task.get("status"));
        assertEquals(100L,((Number)task.get("totalTokens")).longValue());
        assertEquals(2L,((Number)task.get("requestCount")).longValue());
        assertEquals(rollback?0:2,db.queryForObject("SELECT count(*) FROM chunks WHERE record_id=? AND ai_memory_approved",Integer.class,record).intValue());
        assertEquals(rollback?"reviewing":"done",db.queryForObject("SELECT status FROM records WHERE id=?",String.class,record));
        assertNull(UsageTaskScope.current(user),"review scope must not leak to the next operation on this worker");
    }
}
