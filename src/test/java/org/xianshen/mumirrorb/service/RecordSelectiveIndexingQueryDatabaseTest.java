package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL", matches="^jdbc:postgresql:.*$")
class RecordSelectiveIndexingQueryDatabaseTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String VECTOR = "[1" + ",0".repeat(1023) + "]";

    @Test
    void readableSegmentsRespectAuthorizationPagingAndVectorEligibility() throws Exception {
        try (var f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            f.exec("UPDATE chunks SET created_at='2026-10-01T00:00:00Z' WHERE id=3");
            var config=new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            config.setEnvironment(new Environment("fixture",new JdbcTransactionFactory(),new SingleConnectionDataSource(f.connection,true)));
            for (var mapper:List.of(ChatSearchMapper.class,ChunkMapper.class,DailySummaryMapper.class,ProfileStatsMapper.class,UserTermMapper.class)) config.addMapper(mapper);
            {
                // Fixture owns the physical connection and rolls back the schema on close.
                var session=new MybatisSqlSessionFactoryBuilder().build(config).openSession(f.connection);
                var chat=session.getMapper(ChatSearchMapper.class);
                var chunk=session.getMapper(ChunkMapper.class);
                var daily=session.getMapper(DailySummaryMapper.class);
                var stats=session.getMapper(ProfileStatsMapper.class);
                var terms=session.getMapper(UserTermMapper.class);
                var pages=new java.util.ArrayList<>(chat.searchStructured(USER,null,null,null,null,null,2,0).stream().map(RetrievedChunkDTO::getContent).toList());
                pages.addAll(chat.searchStructured(USER,null,null,null,null,null,2,2).stream().map(RetrievedChunkDTO::getContent).toList());
                assertEquals(List.of("ordinary B","ordinary A","indexed"),pages);
                assertEquals(List.of("indexed","ordinary A","ordinary B"),chat.searchByRecordId(USER,1L,20).stream().map(RetrievedChunkDTO::getContent).toList());
                assertEquals(2,chat.searchKeyword(USER,"ordinary",null,null,20,0).size());
                assertEquals(1,chat.searchKeyword(USER,"third",null,null,20,0).size());
                assertTrue(chat.searchKeyword(USER,"secret",null,null,20,0).isEmpty());
                assertEquals(List.of("indexed"),chat.searchSemantic(USER,VECTOR,false,30,null,20).stream().map(RetrievedChunkDTO::getContent).toList());
                assertEquals(List.of("indexed"),chat.searchHybrid(USER,VECTOR,null,null,null,null,null,false,30,null,20).stream().map(RetrievedChunkDTO::getContent).toList());
                assertEquals(1,chunk.searchBySimilarity(USER,VECTOR,20).size());
                var recent=chunk.selectLookbackChunks(USER,null,null,2);
                assertEquals(List.of(2L,3L),recent.stream().map(org.xianshen.mumirrorb.pojo.DO.Chunk::getId).toList());
                assertEquals(java.time.Instant.parse("2026-11-12T00:00:00Z"),recent.get(1).getCreatedAt().toInstant());
                var start=OffsetDateTime.parse("2026-11-01T00:00:00Z"); var end=start.plusMonths(1);
                assertEquals(1,daily.countRecords(USER,start,end));
                assertEquals(3,daily.selectDayRecords(USER,start,end).size());
                assertEquals(1,stats.countUserRecords(USER,start,end));
                assertEquals(3,((Number)stats.selectMoodStats(USER,start,end).get(0).get("count")).intValue());
                assertEquals(1,terms.countRecentRecordHits(USER,List.of("%ordinary%"),start));
                var refs=stats.selectStatsSourceRefs(USER,start,end);
                assertEquals(4,refs.size());
                assertFalse(refs.stream().anyMatch(ref->"chunk".equals(ref.getInputKind()) && ref.getInputId()==4L));
                var tarot=new org.xianshen.mumirrorb.service.impl.TarotContextServiceImpl(new org.springframework.jdbc.core.JdbcTemplate(new SingleConnectionDataSource(f.connection,true)));
                var bundle=tarot.build(USER,"ordinary",new org.xianshen.mumirrorb.tarot.TarotTypes.ReferenceOptions(true,7,false,false),java.time.Instant.parse("2026-11-13T00:00:00Z"));
                assertEquals(1,bundle.materials().size());
                assertTrue(bundle.materials().get(0).excerpt().contains("ordinary"));
                f.exec("INSERT INTO records(id,user_id,content,source,status,user_reviewed,ai_processing_authorized,memory_state,index_policy_version,created_at) VALUES (8,'00000000-0000-0000-0000-000000000001','other record','user','done',true,true,'READY',1,'2026-11-12T00:00:00Z'); INSERT INTO chunks(id,record_id,user_id,content,segment,classified_segment,metadata,ai_memory_approved,embedding_enabled,embedding_status) VALUES (8,8,'00000000-0000-0000-0000-000000000001','other record','new record','new record','{}',true,false,'not_requested')");
                var across=new java.util.ArrayList<>(chat.searchStructured(USER,null,null,null,null,null,2,0).stream().map(RetrievedChunkDTO::getContent).toList());
                across.addAll(chat.searchStructured(USER,null,null,null,null,null,2,2).stream().map(RetrievedChunkDTO::getContent).toList());
                assertEquals(List.of("new record","ordinary B","ordinary A","indexed"),across);
            }
        }
    }

    @Test
    void achievementsCountAuthorizedMemoriesWithoutRequiringTheirVectors() throws Exception {
        try(var f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            var db=new org.springframework.jdbc.core.JdbcTemplate(new SingleConnectionDataSource(f.connection,true));
            var metrics=new org.xianshen.mumirrorb.achievement.AchievementMetrics(db,
                    new org.xianshen.mumirrorb.tarot.TarotCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
            var values=metrics.values(USER,java.util.Set.of("memory.approved_chunks","record.types","record.moods","x.memory.ready_months","x.memory.mood_months"));
            assertEquals(3L,values.get("memory.approved_chunks"));
            assertEquals(1L,values.get("record.types"));
            assertEquals(1L,values.get("record.moods"));
            assertEquals(1L,values.get("x.memory.ready_months"));
            assertEquals(1L,values.get("x.memory.mood_months"));
        }
    }

    @Test
    void offlineReindexSelectsOnlyEnabledReadyVectors() throws Exception {
        try(var f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            var field=org.xianshen.mumirrorb.maintenance.EmbeddingReindexCommandLineRunner.class.getDeclaredField("SELECT_DIARY_TARGETS");
            field.setAccessible(true);
            try(var stmt=f.connection.prepareStatement((String)field.get(null))) {
                stmt.setString(1,USER.toString());
                try(var rs=stmt.executeQuery()) { assertTrue(rs.next()); assertEquals(1L,rs.getLong("id")); assertFalse(rs.next()); }
            }
        }
    }

    @Test
    void revokingUnindexedSourceInvalidatesItsSnapshotWithoutTouchingOtherUsers() throws Exception {
        try(var f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            f.exec("INSERT INTO profile_snapshots(id,user_id,snapshot_type,lineage_tracked) VALUES (1,'00000000-0000-0000-0000-000000000001','manual',true); INSERT INTO snapshot_inputs(snapshot_id,input_kind,input_id,input_role) VALUES (1,'chunk',3,'lookback')");
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.getTypeHandlerRegistry().register(UUID.class,org.xianshen.mumirrorb.common.handler.UuidTypeHandler.class);
            config.setEnvironment(new Environment("fixture",new JdbcTransactionFactory(),new SingleConnectionDataSource(f.connection,true)));
            for(var mapper:List.of(RecordMapper.class,ChunkMapper.class,ChunkEmbeddingJobMapper.class,ProfileSnapshotMapper.class)) config.addMapper(mapper);
            var session=new MybatisSqlSessionFactoryBuilder().build(config).openSession(f.connection);
            var lineage=new org.xianshen.mumirrorb.service.impl.SnapshotLineageService(session.getMapper(ProfileSnapshotMapper.class));
            var lifecycle=new org.xianshen.mumirrorb.service.impl.RecordMemoryLifecycleServiceImpl(session.getMapper(RecordMapper.class),session.getMapper(ChunkMapper.class),session.getMapper(ChunkEmbeddingJobMapper.class),lineage,org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
            lifecycle.disable(USER,1L,0);
            assertEquals(List.of("true"),f.rows("SELECT invalidated::text FROM profile_snapshots WHERE id=1"));
            assertEquals(List.of("false"),f.rows("SELECT ai_memory_approved::text FROM chunks WHERE id=3"));
            assertEquals(List.of("true"),f.rows("SELECT ai_memory_approved::text FROM chunks WHERE id=7"));
        }
    }

    private static void seed(RecordSelectiveIndexingDatabaseTest.Fixture f) throws Exception {
        f.seedUsers();
        f.exec("""
            INSERT INTO records(id,user_id,content,source,status,user_reviewed,ai_processing_authorized,memory_state,index_policy_version,created_at)
            SELECT id,'00000000-0000-0000-0000-000000000001','whole secret','user','done',true,true,'READY',1,'2026-11-12T00:00:00Z' FROM generate_series(1,3) id;
            UPDATE records SET deleted_at=now() WHERE id=2;
            UPDATE records SET index_policy_version=0,memory_state='PENDING' WHERE id=3;
            INSERT INTO chunks(id,record_id,user_id,content,segment,classified_segment,metadata,ai_memory_approved,embedding_enabled,embedding_status,embedding)
            VALUES (1,1,'00000000-0000-0000-0000-000000000001','whole secret','indexed','indexed','{"title":"first","contentType":"learning","mood":["happy"]}',true,true,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector),
            (2,1,'00000000-0000-0000-0000-000000000001','whole secret','ordinary A','ordinary A','{"title":"second","contentType":"learning","mood":["happy"]}',true,false,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector),
            (3,1,'00000000-0000-0000-0000-000000000001','whole secret','ordinary B','ordinary B','{"title":"third","contentType":"learning","mood":["happy"]}',true,false,'not_requested',null),
            (4,1,'00000000-0000-0000-0000-000000000001','whole secret','secret','secret','{}',false,true,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector),
            (5,2,'00000000-0000-0000-0000-000000000001','deleted','deleted','deleted','{}',true,true,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector),
            (6,3,'00000000-0000-0000-0000-000000000001','pending','pending','pending','{}',true,true,'pending',null),
            (7,1,'00000000-0000-0000-0000-000000000002','other','other','other','{}',true,false,'not_requested',null);
            """);
    }
}
