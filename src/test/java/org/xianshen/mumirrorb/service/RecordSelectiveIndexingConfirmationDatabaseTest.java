package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.xianshen.mumirrorb.common.enums.*;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.config.GrowthProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO;
import org.xianshen.mumirrorb.service.impl.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MyBatis services and ledger SQL in disposable, rollback-only fixture schemas. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL", matches="^jdbc:postgresql:.*$")
class RecordSelectiveIndexingConfirmationDatabaseTest {
    @Test
    void policyChangesDuringPreparationKeepOriginalDraftAndRefuseConfirmation() throws Exception {
        try(var s=new Services(1,0)) {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var drafts=mock(RecordDraftService.class);
            var properties=new org.xianshen.mumirrorb.config.RecordMemoryProperties(); properties.setSelectiveIndexEnabled(true);
            var handler=new org.xianshen.mumirrorb.action.RecordActionHandler(drafts,s.records,s.session.getMapper(RecordMapper.class),s.session.getMapper(ChunkMapper.class),json,mock(SettingsService.class));
            ReflectionTestUtils.setField(handler,"recordMemoryProperties",properties);
            var actions=new ActionProposalServiceImpl(s.session.getMapper(ActionProposalMapper.class),List.of(handler));
            var card=actions.prepare(s.user,new org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO("record.create",
                    json.createObjectNode().put("rawContent","keep original").put("mode","AI_ASSISTED"),"preparing-drift",null,null));
            var original=card.getPayload().deepCopy(); properties.setSelectiveIndexEnabled(false);
            actions.completePreparation(s.user,card.getId(),card.getVersion());
            assertEquals(original,actions.get(s.user,card.getId()).getPayload());
            assertEquals("FAILED",actions.get(s.user,card.getId()).getPreparationStatus());
            assertEquals(409,assertThrows(BusinessException.class,()->actions.confirm(s.user,card.getId(),
                    new org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO(card.getVersion(),"confirm-drift"))).getCode());
            verifyNoInteractions(drafts);
        }
    }

    @ParameterizedTest
    @ValueSource(ints={0,3})
    void actualActionCompletesWithBackgroundJobsPreservesRevocationAndRejectsPolicyDrift(int indexed) throws Exception {
        try(var s=new Services(1,0)) {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var ai=mock(AiGrpcClient.class);
            var response=org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse.newBuilder();
            for(int i=0;i<3;i++) response.addItems(org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyItem.newBuilder()
                    .setContent("part"+i).setTitle("part"+i).setEmbeddingEnabled(indexed>0).setEmbeddingReason("fixture"));
            when(ai.classify(eq(s.user),anyString(),eq(true),anyString())).thenReturn(response.build());
            var settings=mock(SettingsService.class);
            var properties=new org.xianshen.mumirrorb.config.RecordMemoryProperties(); properties.setSelectiveIndexEnabled(true);
            var drafts=new RecordDraftServiceImpl(ai,settings);
            ReflectionTestUtils.setField(drafts,"recordMemoryProperties",properties);
            var handler=new org.xianshen.mumirrorb.action.RecordActionHandler(drafts,s.records,s.session.getMapper(RecordMapper.class),s.session.getMapper(ChunkMapper.class),json,settings);
            ReflectionTestUtils.setField(handler,"recordMemoryProperties",properties);
            var actions=new ActionProposalServiceImpl(s.session.getMapper(ActionProposalMapper.class),List.of(handler));
            var card=actions.prepare(s.user,new org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO("record.create",
                    json.createObjectNode().put("rawContent","part0\npart1\npart2").put("mode","AI_ASSISTED"),"selective-card",null,null));
            actions.completePreparation(s.user,card.getId(),card.getVersion());
            card=actions.get(s.user,card.getId());
            var edited=(com.fasterxml.jackson.databind.node.ObjectNode)card.getPayload().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)edited.path("recordDraft").path("segments").get(2)).put("memoryEnabled",false);
            card=actions.edit(s.user,card.getId(),new org.xianshen.mumirrorb.pojo.DTO.ActionEditDTO(card.getVersion(),edited));
            var unchanged=card.getPayload().deepCopy();
            properties.setSelectiveIndexEnabled(false);
            var pending=card;
            assertEquals(409,assertThrows(BusinessException.class,()->actions.confirm(s.user,pending.getId(),
                    new org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO(pending.getVersion(),"drift"))).getCode());
            assertEquals(unchanged,actions.get(s.user,card.getId()).getPayload());
            assertEquals("PENDING",actions.get(s.user,card.getId()).getState());
            properties.setSelectiveIndexEnabled(true);
            var confirmed=actions.confirm(s.user,card.getId(),new org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO(card.getVersion(),"confirm"));
            assertEquals("SUCCEEDED",confirmed.getState());
            assertEquals("APPLIED",confirmed.getResult().path("outcome").asText());
            assertEquals("READY",confirmed.getResult().path("memoryState").asText());
            assertEquals(List.of("2"),s.f.rows("SELECT count(*)::text FROM "+s.f.ai+".record_chunks"));
            assertEquals(List.of(indexed==0?"0":"2"),s.f.rows("SELECT count(*)::text FROM chunk_embedding_jobs"));
            verify(ai,times(1)).classify(eq(s.user),anyString(),eq(true),anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(ints={0,1,3})
    void confirmationMakesThreeAuthorizedChunksReadableWithOnlySelectedJobs(int indexed) throws Exception {
        try (var s = new Services(1, indexed)) {
            var confirmed = s.confirm(List.of(100L,101L,102L));
            assertEquals(RecordMemoryState.READY, confirmed.getMemoryState());
            assertEquals(RecordStatus.DONE, confirmed.getStatus());
            assertTrue(confirmed.getUserReviewed());
            assertEquals(List.of("3"), s.f.rows("SELECT count(*)::text FROM " + s.f.ai + ".record_chunks"));
            assertEquals(List.of(String.valueOf(indexed)), s.f.rows("SELECT count(*)::text FROM chunk_embedding_jobs"));
            assertEquals(List.of("1"), s.f.rows("SELECT count(*)::text FROM growth_entries"));
            assertEquals(List.of("1"), s.f.rows("SELECT count(*)::text FROM todo_registry"));
            assertEquals(List.of(String.valueOf(3-indexed)), s.f.rows("SELECT count(*)::text FROM chunks WHERE NOT embedding_enabled AND embedding_status='not_requested' AND ai_memory_approved"));
            assertThrows(BusinessException.class, () -> s.confirm(List.of(100L,101L,102L)));
            assertEquals(List.of("1"), s.f.rows("SELECT count(*)::text FROM growth_entries"));
        }
    }

    @Test
    void indexingFailureRetainsAuthorizationGrowthAndTodosAndCallbacksDoNotRescore() throws Exception {
        try (var s = new Services(1,1)) {
            s.confirm(List.of(100L,101L,102L));
            var job = s.jobs.claimDue(1, Duration.ofSeconds(30)).get(0);
            assertEquals("segment 0", s.jobs.currentAuthorizedText(job));
            s.jobs.markFailure(job,"timeout",1,Duration.ofSeconds(1));
            s.lifecycle.onInitialIndexExhausted(s.user,100L,2,"timeout");
            s.lifecycle.onInitialIndexComplete(s.user,100L,2);
            assertEquals(List.of("true"),s.f.rows("SELECT record_ai_is_readable(100)::text"));
            assertEquals(List.of("READY:true:true"),s.f.rows("SELECT memory_state||':'||user_reviewed||':'||ai_processing_authorized FROM records WHERE id=100"));
            assertEquals(List.of("1"),s.f.rows("SELECT count(*)::text FROM growth_entries"));
            assertEquals(List.of("1"),s.f.rows("SELECT count(*)::text FROM todo_registry"));
            assertEquals(List.of("failed"),s.f.rows("SELECT embedding_status FROM chunks WHERE id=100"));
            assertThrows(BusinessException.class, () -> s.jobs.retry(101L,s.user));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void disableOrDeleteRejectsLateReadyAndFailureWithoutRestoringSkippedChunks(boolean deleted) throws Exception {
        try (var s = new Services(1,1)) {
            s.confirm(List.of(100L,101L,102L));
            var job = s.jobs.claimDue(1,Duration.ofSeconds(30)).get(0);
            if(deleted) s.records.softDelete(100L,s.user); else s.lifecycle.disable(s.user,100L,2);
            assertNull(s.jobs.currentAuthorizedText(job));
            s.jobs.markReady(job, java.util.Collections.nCopies(1024,0.1f));
            s.jobs.markFailure(job,"late timeout",1,Duration.ZERO);
            assertEquals(List.of("false"),s.f.rows("SELECT record_ai_is_readable(100)::text"));
            assertEquals(List.of("0"),s.f.rows("SELECT count(*)::text FROM chunks WHERE ai_memory_approved OR embedding IS NOT NULL"));
            assertEquals(List.of("cancelled"),s.f.rows("SELECT status FROM chunk_embedding_jobs"));
        }
    }

    @Test
    void atomicWritesAndWorkerReadsRejectChangedInputsBeforeAcceptingCurrentVersion() throws Exception {
        try(var s=new Services(1,1)) {
            s.confirm(List.of(100L,101L,102L));
            var job=s.jobs.claimDue(1,Duration.ofSeconds(30)).get(0);
            var mapper=s.session.getMapper(ChunkMapper.class);
            for(String change:List.of("UPDATE chunks SET embedding_enabled=false WHERE id=100",
                    "UPDATE chunks SET ai_memory_approved=false WHERE id=100",
                    "UPDATE chunks SET segment='different text' WHERE id=100",
                    "UPDATE chunks SET content_version=2 WHERE id=100",
                    "UPDATE chunks SET user_id='00000000-0000-0000-0000-000000000002' WHERE id=100",
                    "UPDATE records SET user_id='00000000-0000-0000-0000-000000000002' WHERE id=100",
                    "UPDATE records SET deleted_at=now() WHERE id=100",
                    "UPDATE records SET ai_processing_authorized=false WHERE id=100",
                    "UPDATE records SET user_reviewed=false WHERE id=100",
                    "UPDATE records SET status='reviewing' WHERE id=100",
                    "UPDATE records SET memory_state='DISABLED' WHERE id=100",
                    "UPDATE chunk_embedding_jobs SET status='cancelled'",
                    "UPDATE chunk_embedding_jobs SET content_hash=repeat('0',64)")) {
                var point=s.f.connection.setSavepoint(); s.f.exec(change);
                assertNull(s.jobs.currentAuthorizedText(job),change);
                assertEquals(0,mapper.saveEmbeddingIfCurrent(job.getChunkId(),s.user,job.getContentVersion(),job.getId(),java.util.Collections.nCopies(1024,0.1f)),change);
                s.jobs.markFailure(job,"stale",1,Duration.ZERO);
                assertEquals(List.of("cancelled"),s.f.rows("SELECT status FROM chunk_embedding_jobs"));
                s.f.connection.rollback(point);
            }
            s.jobs.markReady(job,java.util.Collections.nCopies(1024,0.1f));
            assertEquals(List.of("ready"),s.f.rows("SELECT embedding_status FROM chunks WHERE id=100"));
            assertEquals(List.of("true"),s.f.rows("SELECT record_ai_is_readable(100)::text"));
            assertEquals(List.of("1"),s.f.rows("SELECT count(*)::text FROM growth_entries"));
        }
    }

    @Test
    void versionZeroStillWaitsAndRetainsItsExhaustionFallback() throws Exception {
        try (var s = new Services(0,3)) {
            var confirmed=s.confirm(List.of(100L,101L,102L));
            assertEquals(RecordMemoryState.PENDING,confirmed.getMemoryState());
            assertEquals(List.of("0"),s.f.rows("SELECT count(*)::text FROM growth_entries"));
            assertNotNull(s.jobs.currentAuthorizedText(s.jobs.claimDue(1,Duration.ofSeconds(30)).get(0)),"legacy PENDING jobs must remain runnable");
            s.lifecycle.onInitialIndexExhausted(s.user,100L,2,"timeout");
            assertEquals(List.of("record_only:false:false"),s.f.rows("SELECT status||':'||user_reviewed||':'||ai_processing_authorized FROM records WHERE id=100"));
            assertEquals(List.of("1"),s.f.rows("SELECT count(*)::text FROM todo_registry"));
        }
    }

    @Test
    void noMemoryApprovalRemainsUnreadableAndReviewedTextRemainsReadonly() throws Exception {
        try(var s=new Services(1,0)) {
            var confirmed=s.confirm(List.of());
            assertEquals(RecordMemoryState.NONE,confirmed.getMemoryState());
            assertEquals(List.of("false"),s.f.rows("SELECT record_ai_is_readable(100)::text"));
            assertThrows(BusinessException.class,()->s.records.updateRaw(100L,"changed",s.user));
            assertThrows(BusinessException.class,()->s.chunks.delete(100L,s.user));
        }
    }

    private static final class Services implements AutoCloseable {
        final RecordSelectiveIndexingDatabaseTest.Fixture f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true);
        final UUID user=UUID.fromString("00000000-0000-0000-0000-000000000001");
        final SqlSession session;
        final RecordServiceImpl records;
        final ChunkServiceImpl chunks;
        final ChunkEmbeddingJobServiceImpl jobs;
        final RecordMemoryLifecycleServiceImpl lifecycle;
        final GrowthServiceImpl growth;

        Services(int policy,int indexed) throws Exception {
            var configuration=new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
            configuration.setEnvironment(new Environment("isolated",new JdbcTransactionFactory(),new SingleConnectionDataSource(f.connection,true)));
            for(var mapper:List.of(RecordMapper.class,ChunkMapper.class,ChunkEmbeddingJobMapper.class,
                    GrowthAxisMapper.class,GrowthEntryMapper.class,GrowthAxisHistoryMapper.class,GrowthEntryHistoryMapper.class,
                    ProfileSnapshotMapper.class,TodoRegistryMapper.class,TodoRegistryLinkMapper.class,TodoSuggestionMapper.class,ActionProposalMapper.class)) configuration.addMapper(mapper);
            session=new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(f.connection);
            var rm=session.getMapper(RecordMapper.class); var cm=session.getMapper(ChunkMapper.class);
            var jm=session.getMapper(ChunkEmbeddingJobMapper.class);
            var events=mock(ApplicationEventPublisher.class);
            var lineage=new SnapshotLineageService(session.getMapper(ProfileSnapshotMapper.class));
            lifecycle=new RecordMemoryLifecycleServiceImpl(rm,cm,jm,lineage,events);
            jobs=new ChunkEmbeddingJobServiceImpl(jm,cm,rm,lifecycle,events);
            growth=new GrowthServiceImpl(session.getMapper(GrowthAxisMapper.class),session.getMapper(GrowthEntryMapper.class),
                    session.getMapper(GrowthAxisHistoryMapper.class),session.getMapper(GrowthEntryHistoryMapper.class),new GrowthProperties());
            ReflectionTestUtils.setField(lifecycle,"growthService",growth);
            var todos=new TodoRegistryServiceImpl(session.getMapper(TodoRegistryMapper.class),session.getMapper(TodoRegistryLinkMapper.class),session.getMapper(TodoSuggestionMapper.class),cm,rm);
            var context=mock(ApplicationContext.class); when(context.getBean(TodoRegistryService.class)).thenReturn(todos);
            records=new RecordServiceImpl(rm,cm,lineage,events,mock(AiGrpcClient.class),context,jobs);
            ReflectionTestUtils.setField(records,"growthService",growth);
            ReflectionTestUtils.setField(records,"growthEntries",session.getMapper(GrowthEntryMapper.class));
            chunks=new ChunkServiceImpl(cm,rm,lineage,mock(AiGrpcClient.class),jobs);
            f.seedUsers();
            rm.insert(Record.builder().id(100L).userId(user).content("original").source("user")
                    .status(RecordStatus.REVIEWING).aiProcessingAuthorized(true).userReviewed(false)
                    .memoryState(RecordMemoryState.NONE).indexPolicyVersion(policy).processingVersion(1)
                    .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
            for(int i=0;i<3;i++) cm.insert(Chunk.builder().id(100L+i).recordId(100L).userId(user)
                    .content("original").segment("segment "+i).classifiedSegment("segment "+i)
                    .metadata(Map.of("contentType",i==0?"todo":"health","title","segment "+i,"embeddingReason","fixture"))
                    .embeddingEnabled(i<indexed).aiMemoryApproved(false).todoApproved(false)
                    .contentVersion(1L).embeddingStatus("not_requested").build());
        }

        org.xianshen.mumirrorb.pojo.VO.RecordVO confirm(List<Long> approvals) {
            var adjustment=new GrowthAdjustmentDTO(); adjustment.setDimensionId("knowledge"); adjustment.setPoints(3); adjustment.setReason("confirmed");
            return records.confirmReview(100L,user,approvals,List.of(100L),List.of(),UUID.randomUUID(),2,List.of(adjustment),growth.axesVersion(user));
        }
        public void close() throws Exception { try { f.connection.rollback(); } finally { session.close(); } }
    }
}
