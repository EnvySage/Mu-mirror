package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.tools.impl.SearchRecordsTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL regressions for search_records paging after diary, text, and mode filters. */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
class LegacySearchPaginationDatabaseTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void structuredQueryPagesSkipNonmatchesAndVaultBeforeOffset() throws Exception {
        try (var f = RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            var mapper = mapper(f);
            var tool = tool(mapper, false);

            Map<String, Object> first = payload(tool.execute(USER,
                    Map.of("mode", "structured", "query", "MATCH", "limit", 1)));
            Map<String, Object> second = payload(tool.execute(USER,
                    Map.of("mode", "structured", "query", "MATCH", "limit", 1,
                            "offset", first.get("next_offset"))));

            assertEquals(1, first.get("count"));
            assertEquals(1L, first.get("next_offset"));
            assertEquals(1L, rows(first).get(0).get("record_id"));
            assertEquals(1, second.get("count"));
            assertNull(second.get("next_offset"));
            assertFalse((Boolean) second.get("truncated"));
            assertEquals(4L, rows(second).get(0).get("record_id"));
            assertEquals(List.of(1L, 4L), List.of(rows(first).get(0).get("record_id"), rows(second).get(0).get("record_id")));

            Map<String, Object> byRecord = payload(tool.execute(USER,
                    Map.of("record_id", 1, "query", "title-only-marker", "limit", 1)));
            assertEquals(1, byRecord.get("count"));
            assertEquals(1L, rows(byRecord).get(0).get("record_id"));
        }
    }

    @Test
    void keywordSemanticAndHybridPagesExcludeVaultBeforeOffset() throws Exception {
        try (var f = RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            seed(f);
            var mapper = mapper(f);
            var tool = tool(mapper, true);

            Map<String, Object> keywordFirst = payload(tool.execute(USER,
                    Map.of("mode", "keyword", "keyword", "MATCH", "limit", 1)));
            Map<String, Object> keywordSecond = payload(tool.execute(USER,
                    Map.of("mode", "keyword", "keyword", "MATCH", "limit", 1,
                            "offset", keywordFirst.get("next_offset"))));
            assertEquals(List.of(1L, 4L), List.of(rows(keywordFirst).get(0).get("record_id"), rows(keywordSecond).get(0).get("record_id")));
            assertNull(keywordSecond.get("next_offset"));

            for (String mode : List.of("semantic", "hybrid")) {
                Map<String, Object> args = mode.equals("semantic")
                        ? Map.of("mode", mode, "query", "MATCH", "limit", 1)
                        : Map.of("mode", mode, "query", "MATCH", "content_type", "learning", "limit", 1);
                Map<String, Object> first = payload(tool.execute(USER, args));
                Map<String, Object> secondArgs = mode.equals("semantic")
                        ? Map.of("mode", mode, "query", "MATCH", "limit", 1, "offset", first.get("next_offset"))
                        : Map.of("mode", mode, "query", "MATCH", "content_type", "learning",
                                "limit", 1, "offset", first.get("next_offset"));
                Map<String, Object> second = payload(tool.execute(USER, secondArgs));
                assertEquals(1L, rows(first).get(0).get("record_id"));
                assertEquals(4L, rows(second).get(0).get("record_id"));
                assertNull(second.get("next_offset"));
            }
        }
    }

    private static ChatSearchMapper mapper(RecordSelectiveIndexingDatabaseTest.Fixture f) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("fixture", new JdbcTransactionFactory(),
                new SingleConnectionDataSource(f.connection, true)));
        config.addMapper(ChatSearchMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(config).openSession(f.connection)
                .getMapper(ChatSearchMapper.class);
    }

    private static SearchRecordsTool tool(ChatSearchMapper mapper, boolean vectorSearch) {
        var ai = mock(AiGrpcClient.class);
        if (vectorSearch) {
            when(ai.embed(USER, "MATCH")).thenReturn(EmbeddingProto.EmbedResponse.newBuilder()
                    .setDimension(1024).addVector(1f).addAllVector(java.util.Collections.nCopies(1023, 0f)).build());
        }
        var settings = mock(SettingsMapper.class);
        when(settings.selectOne(any())).thenReturn(UserSettings.builder().ragHalfLife(30).build());
        var props = new MirrorProperties();
        props.setRagMaxCosineDistance(0.0);
        return new SearchRecordsTool(mapper, ai, settings, props);
    }

    private static void seed(RecordSelectiveIndexingDatabaseTest.Fixture f) throws Exception {
        f.seedUsers();
        f.exec("""
            INSERT INTO vault_items(id,user_id,original_name,storage_key,size_bytes,mime)
            VALUES (200,'00000000-0000-0000-0000-000000000001','vault.txt','test/vault.txt',10,'text/plain');
            INSERT INTO records(id,user_id,content,source,status,user_reviewed,ai_processing_authorized,memory_state,index_policy_version,created_at)
            VALUES
              (1,'00000000-0000-0000-0000-000000000001','MATCH first','user','done',true,true,'READY',1,'2026-11-13T04:00:00Z'),
              (2,'00000000-0000-0000-0000-000000000001','unrelated ordinary day','user','done',true,true,'READY',1,'2026-11-13T03:00:00Z'),
              (3,'00000000-0000-0000-0000-000000000001','MATCH vault','vault','done',true,true,'READY',1,'2026-11-13T02:00:00Z'),
              (4,'00000000-0000-0000-0000-000000000001','MATCH second','user','done',true,true,'READY',1,'2026-11-13T01:00:00Z');
            INSERT INTO chunks(id,record_id,user_id,content,segment,classified_segment,metadata,ai_memory_approved,embedding_enabled,embedding_status,embedding,vault_item_id)
            VALUES
              (1,1,'00000000-0000-0000-0000-000000000001','MATCH first','MATCH first','MATCH first','{"title":"title-only-marker","contentType":"learning"}',true,true,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector,null),
              (2,2,'00000000-0000-0000-0000-000000000001','unrelated ordinary day','unrelated ordinary day','unrelated ordinary day','{"title":"other","contentType":"learning"}',true,false,'not_requested',null,null),
              (3,3,'00000000-0000-0000-0000-000000000001','MATCH vault','MATCH vault','MATCH vault','{"title":"MATCH vault","keyChunk":"true","contentType":"learning"}',false,true,'ready',array_prepend(1::real,array_fill(0::real,ARRAY[1023]))::vector,200),
              (4,4,'00000000-0000-0000-0000-000000000001','MATCH second','MATCH second','MATCH second','{"title":"second","contentType":"learning"}',true,true,'ready',array_prepend((-1)::real,array_fill(0::real,ARRAY[1023]))::vector,null);
            """);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(org.xianshen.mumirrorb.tools.ToolExecutionResult result) {
        return (Map<String, Object>) result.getPayload();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("records");
    }
}
