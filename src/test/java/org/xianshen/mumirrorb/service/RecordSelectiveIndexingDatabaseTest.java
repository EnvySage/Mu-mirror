package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Default/mapping tests always run; PostgreSQL assertions require an explicitly isolated test DB. */
class RecordSelectiveIndexingDatabaseTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void oldChunkBuildersDefaultToIndexingRatherThanSilentlySkipping() {
        JsonNode built = json.valueToTree(Chunk.builder().segment("old caller").build());
        assertTrue(built.hasNonNull("embeddingEnabled"), "old builders must retain indexing by default");
        assertTrue(built.get("embeddingEnabled").booleanValue());
        assertTrue(json.valueToTree(new Chunk()).get("embeddingEnabled").booleanValue());
        assertFalse(Chunk.builder().embeddingEnabled(false).build().getEmbeddingEnabled());
        assertTrue(Chunk.builder().embeddingEnabled(null).build().getEmbeddingEnabled());
    }

    @Test
    void recordDetailsExposeCompatibilityVersionAndIndexingReasonWithoutVectorBody() {
        UUID user = UUID.randomUUID();
        RecordMapper records = mock(RecordMapper.class);
        ChunkMapper chunks = mock(ChunkMapper.class);
        when(records.selectOne(any())).thenReturn(Record.builder().id(1L).userId(user)
                .source("user").status(RecordStatus.DONE).build());
        when(chunks.selectList(any())).thenReturn(List.of(Chunk.builder().id(2L).recordId(1L)
                .userId(user).segment("ordinary day").metadata(Map.of("embeddingReason", "routine"))
                .embeddingStatus("not_requested").embedding(List.of(1f)).build()));
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Chunk.class);
        RecordServiceImpl service = new RecordServiceImpl(records, chunks, null, null, null, null, null);
        JsonNode detail = json.valueToTree(service.getById(1L, user));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Wrapper<Chunk>> chunkQuery = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(chunks).selectList(chunkQuery.capture());
        assertTrue(chunkQuery.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT).contains("order by id asc"),
                "record details keep segment order stable for per-segment authorization and indexing state");
        assertTrue(detail.hasNonNull("indexPolicyVersion"));
        assertEquals(0, detail.get("indexPolicyVersion").intValue());
        JsonNode chunk = detail.get("chunks").get(0);
        assertTrue(chunk.get("embeddingEnabled").booleanValue());
        assertEquals("routine", chunk.get("embeddingReason").textValue());
        assertFalse(chunk.has("embedding"));
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
    class PostgreSql {
        @Test
        void releaseRefusesIncompleteV17PrerequisitesBeforeMigration() throws Exception {
            try (Fixture f = Fixture.open(true)) {
                assertEquals(0, releaseRefusalExit(f), "complete fresh fixture meets release prerequisites");
                for (String remove : List.of(
                        "ALTER TABLE user_achievement RENAME COLUMN plating_rule_version TO missing_marker",
                        "ALTER TABLE agent_runs RENAME COLUMN achievement_settings TO missing_marker",
                        "ALTER FUNCTION achievement_observe(uuid,text,text,text,text,text,timestamptz) RENAME TO missing_marker",
                        "ALTER FUNCTION achievement_connection_key(uuid,text) RENAME TO missing_marker",
                        "DROP TRIGGER achievement_observed ON user_achievement_activity",
                        "ALTER INDEX user_achievement_plating_pending RENAME TO missing_marker",
                        "ALTER VIEW " + f.ai + ".glossary_terms RENAME COLUMN term TO missing_marker")) {
                    var savepoint = f.connection.setSavepoint();
                    f.exec(remove);
                    assertEquals(42, releaseRefusalExit(f), "release must refuse missing prerequisite: " + remove);
                    f.connection.rollback(savepoint);
                }
            }
        }

        @Test
        void replayPreservesExistingVersionOneAndExplicitSkippedIndexing() throws Exception {
            try (Fixture f = Fixture.open(true)) {
                f.seedSelective();
                List<String> records = f.rows("SELECT to_jsonb(r)::text FROM records r ORDER BY id");
                List<String> chunks = f.rows("SELECT to_jsonb(c)::text FROM chunks c ORDER BY id");
                List<String> visible = f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id");
                f.migrate();
                f.migrate();
                assertEquals(records, f.rows("SELECT to_jsonb(r)::text FROM records r ORDER BY id"));
                assertEquals(chunks, f.rows("SELECT to_jsonb(c)::text FROM chunks c ORDER BY id"));
                assertEquals(visible, f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id"));
                assertEquals(List.of("1:false"), f.rows("SELECT r.index_policy_version||':'||c.embedding_enabled FROM records r JOIN chunks c ON c.record_id=r.id WHERE r.id=1"));
            }
        }

        @Test
        void replayTwicePreservesOldRowsAndVisibleSetWithConservativeDefaults() throws Exception {
            try (Fixture f = Fixture.open(false)) {
                f.seedLegacy();
                List<String> recordsBefore = f.rows("SELECT to_jsonb(r)::text FROM records r ORDER BY id");
                List<String> chunksBefore = f.rows("SELECT to_jsonb(c)::text FROM chunks c ORDER BY id");
                List<String> visible = f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id");
                f.migrate();
                f.migrate();
                assertEquals(recordsBefore, f.rows("SELECT (to_jsonb(r)-'index_policy_version')::text FROM records r ORDER BY id"),
                        "original content, approval, vectors and lifecycle state must survive migration");
                assertEquals(chunksBefore, f.rows("SELECT (to_jsonb(c)-'embedding_enabled')::text FROM chunks c ORDER BY id"));
                assertEquals(visible, f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id"));
                assertEquals(List.of("0:true", "0:true"), f.rows("SELECT r.index_policy_version||':'||c.embedding_enabled "
                        + "FROM records r JOIN chunks c ON c.record_id=r.id ORDER BY r.id"));
            }
        }

        @Test
        void freshAndUpgradeShareReadableGateAndAppendOnlyViewColumns() throws Exception {
            try (Fixture upgraded = Fixture.open(false); Fixture fresh = Fixture.open(true)) {
                upgraded.migrate();
                assertEquals(fresh.columns("records"), upgraded.columns("records"));
                assertEquals(fresh.columns("chunks"), upgraded.columns("chunks"));
                assertEquals(List.of("chunk_id", "record_id", "user_id", "segment", "metadata", "embedding", "created_at",
                        "embedding_enabled", "embedding_status", "index_policy_version"), fresh.viewColumns());
                assertEquals(fresh.viewColumns(), upgraded.viewColumns());
                for (Fixture f : List.of(upgraded, fresh)) {
                    f.seedSelective();
                    assertEquals(List.of("1", "2", "3"), f.rows("SELECT id::text FROM records WHERE record_ai_is_readable(id) ORDER BY id"));
                    assertEquals(List.of("1", "2", "3"), f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id"));
                    assertEquals(List.of("false", "false", "false"), f.rows("SELECT record_memory_is_complete(id)::text FROM records WHERE id<=3 ORDER BY id"));
                    assertTrue(f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks WHERE user_id='00000000-0000-0000-0000-000000000002'").isEmpty());
                    assertEquals(List.of("security_barrier=true"), f.rows("SELECT unnest(reloptions) FROM pg_class WHERE oid='" + f.ai + ".record_chunks'::regclass"));
                    assertThrows(SQLException.class, () -> f.exec("INSERT INTO records(id,user_id,content,index_policy_version) VALUES (99,'00000000-0000-0000-0000-000000000001','invalid',2)"));
                }
            }
        }

        @Test
        void provisionedReaderCanReadEightViewsWithoutBaseTableOrFunctionDependencies() throws Exception {
            // Requires the pre-provisioned role, never creates/changes cluster roles.
            try (Fixture f = Fixture.open(true)) {
                f.seedSelective();
                String role = "mirror_ai_reader";
                assertEquals(List.of("true"), f.rows("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='" + role + "')::text"),
                        "provision mirror_ai_reader in the isolated test DB before running reader coverage");
                f.exec("GRANT USAGE ON SCHEMA " + f.ai + " TO " + role);
                String grants = Files.readString(Path.of("deploy/agent-reader-role.sql"));
                var block = Pattern.compile("GRANT SELECT ON\\s+(.+?)TO :\\\"reader_role\\\";", Pattern.DOTALL).matcher(grants);
                assertTrue(block.find(), "reader deployment script must supply the grant contract");
                List<String> expected = Pattern.compile("mirror_ai\\.([a-z_]+)").matcher(block.group(1)).results()
                        .map(match -> match.group(1)).sorted().toList();
                assertEquals(List.of("chat_history", "glossary_terms", "profiles", "record_chunks", "todos", "vault_content", "vault_keys", "vault_metadata"), expected);
                // Execute the production grant clause, not a separately authored test grant list.
                f.exec(block.group().replace("mirror_ai.", f.ai + ".").replace(":\"reader_role\"", role));
                List<String> baseTables = f.rows("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='" + f.schema + "' AND c.relkind IN ('r','p') ORDER BY c.relname");
                assertFalse(baseTables.isEmpty());
                f.exec("SET LOCAL ROLE " + role);
                assertEquals(List.of("1", "2", "3"), f.rows("SELECT chunk_id::text FROM " + f.ai + ".record_chunks ORDER BY chunk_id"));
                assertEquals(expected.stream().map(view -> f.ai + "." + view).toList(), f.rows("SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('" + f.schema + "','" + f.ai + "') AND c.relkind IN ('r','p','v','m','f','S') AND has_table_privilege(current_user,c.oid,'SELECT') ORDER BY n.nspname,c.relname"));
                for (String table : baseTables) {
                    assertEquals(List.of("false"), f.rows("SELECT has_table_privilege(current_user,c.oid,'SELECT')::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='" + f.schema + "' AND c.relname='" + table + "'"));
                }
                var savepoint = f.connection.setSavepoint();
                assertThrows(SQLException.class, () -> f.rows("SELECT id::text FROM " + f.schema + ".records"));
                f.connection.rollback(savepoint);
            }
        }
    }

    private static int releaseRefusalExit(Fixture f) throws Exception {
        String script = Files.readString(Path.of("deploy/backend-release.sh")).replace("\r\n", "\n");
        String sqlStart = "-Atqc \"";
        int start = script.indexOf(sqlStart) + sqlStart.length();
        String query = script.substring(start, script.indexOf("\n  \")\"", start))
                .replace("public.", f.schema + ".").replace("'public'", "'" + f.schema + "'").replace("mirror_ai", f.ai);
        String result = f.rows(query).get(0);
        String refusal = script.lines().filter(line -> line.contains("[[ \"$BASELINE_READY\" == \"t\" ]]")).findFirst().orElseThrow();
        String bash = Files.exists(Path.of("D:/Git/bin/bash.exe")) ? "D:/Git/bin/bash.exe" : "bash";
        Process process = new ProcessBuilder(bash, "-c", "die() { exit 42; }; BASELINE_READY='" + result + "'; " + refusal)
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        return process.exitValue();
    }

    static final class Fixture implements AutoCloseable {
        final Connection connection;
        final String schema = "test_selective_" + UUID.randomUUID().toString().replace("-", "");
        final String ai = schema + "_ai";

        private Fixture(Connection connection) { this.connection = connection; }

        static Fixture open(boolean fresh) throws Exception {
            Fixture f = new Fixture(DriverManager.getConnection(System.getenv("MIRROR_TEST_DB_URL"),
                    System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD")));
            try {
                f.connection.setAutoCommit(false);
                f.exec("CREATE SCHEMA " + f.schema);
                f.exec("CREATE SCHEMA " + f.ai);
                f.exec("SET LOCAL search_path TO " + f.schema + ", public");
                assertEquals(List.of("true"), f.rows("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')::text"), "pgvector must already be provisioned");
                if (fresh) {
                    f.exec(resource("schema.sql").replace("CREATE EXTENSION IF NOT EXISTS vector;", "").replace("mirror_ai", f.ai));
                } else {
                    // Minimal v17 record surface. The historical definitions come from their owning migrations.
                    f.exec("CREATE TABLE records(id BIGINT PRIMARY KEY,user_id UUID NOT NULL,content TEXT NOT NULL,source VARCHAR(20) DEFAULT 'user',status VARCHAR(20) DEFAULT 'processing',user_reviewed BOOLEAN DEFAULT FALSE,ai_processing_authorized BOOLEAN NOT NULL DEFAULT FALSE,processing_version INTEGER NOT NULL DEFAULT 0,created_at TIMESTAMPTZ DEFAULT now(),deleted_at TIMESTAMPTZ,memory_state VARCHAR(24) NOT NULL DEFAULT 'NONE');"
                            + "CREATE TABLE chunks(id BIGINT PRIMARY KEY,record_id BIGINT NOT NULL,user_id UUID NOT NULL,content TEXT NOT NULL,segment TEXT,metadata JSONB,embedding vector(1024),ai_memory_approved BOOLEAN NOT NULL DEFAULT FALSE,embedding_status VARCHAR(24) NOT NULL DEFAULT 'not_requested');");
                    String oldFunction = resource("migration-v8-record-memory-state.sql");
                    f.exec(oldFunction.substring(oldFunction.indexOf("CREATE OR REPLACE FUNCTION record_memory_is_complete")));
                    String oldView = resource("migration-v11-agent-v2.sql");
                    f.exec(oldView.substring(oldView.indexOf("CREATE OR REPLACE VIEW mirror_ai.record_chunks"), oldView.indexOf("CREATE OR REPLACE VIEW mirror_ai.glossary_terms")).replace("mirror_ai", f.ai));
                }
                return f;
            } catch (Throwable failure) { f.close(); throw failure; }
        }

        void migrate() throws Exception { exec(resource("migration-v18-record-selective-index.sql").replace("mirror_ai", ai)); }

        void seedLegacy() throws SQLException {
            seedUsers();
            exec("INSERT INTO records(id,user_id,content,status,user_reviewed,ai_processing_authorized,memory_state) VALUES "
                    + "(1,'00000000-0000-0000-0000-000000000001','old ready','done',true,true,'READY'),"
                    + "(2,'00000000-0000-0000-0000-000000000001','old pending','done',true,true,'PENDING');"
                    + "INSERT INTO chunks(id,record_id,user_id,content,segment,metadata,embedding,ai_memory_approved,embedding_status) SELECT id,id,user_id,content,content,'{\"original\":true}',CASE WHEN id=1 THEN array_fill(0::real,ARRAY[1024])::vector ELSE NULL END,true,CASE WHEN id=1 THEN 'ready' ELSE 'pending' END FROM records;");
        }

        void seedUsers() throws SQLException {
            if (rows("SELECT to_regclass('" + schema + ".users') IS NOT NULL").equals(List.of("t"))) {
                exec("INSERT INTO users(id,username,password_hash) VALUES ('00000000-0000-0000-0000-000000000001','selective_owner','test'),('00000000-0000-0000-0000-000000000002','selective_other','test')");
            }
        }

        void seedSelective() throws SQLException {
            seedUsers();
            exec("INSERT INTO records(id,user_id,content,status,user_reviewed,ai_processing_authorized,memory_state,index_policy_version) SELECT id,'00000000-0000-0000-0000-000000000001'::uuid,'record '||id,'done',true,true,'READY',1 FROM generate_series(1,14) id;"
                    + "UPDATE records SET status='reviewing' WHERE id=4; UPDATE records SET ai_processing_authorized=false WHERE id=5; UPDATE records SET deleted_at=now() WHERE id=6; UPDATE records SET memory_state='DISABLED' WHERE id=7; UPDATE records SET user_reviewed=false WHERE id=8; UPDATE records SET index_policy_version=0,memory_state='PENDING' WHERE id=9;"
                    + "INSERT INTO chunks(id,record_id,user_id,content,segment,ai_memory_approved,embedding_enabled,embedding_status) SELECT id,id,user_id,content,'authorized '||id,true,false,'not_requested' FROM records;"
                    + "UPDATE chunks SET embedding_enabled=true,embedding_status='pending' WHERE id=2; UPDATE chunks SET embedding_enabled=true,embedding_status='failed' WHERE id=3; UPDATE chunks SET user_id='00000000-0000-0000-0000-000000000002' WHERE id=10; UPDATE chunks SET ai_memory_approved=false WHERE id=11; UPDATE chunks SET segment=' ' WHERE id=12; UPDATE records SET source='system' WHERE id=13; UPDATE records SET memory_state='PENDING' WHERE id=14;");
        }

        List<String> columns(String table) throws SQLException {
            return rows("SELECT column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "' AND column_name IN ('index_policy_version','embedding_enabled') ORDER BY column_name");
        }
        List<String> viewColumns() throws SQLException { return rows("SELECT column_name FROM information_schema.columns WHERE table_schema='" + ai + "' AND table_name='record_chunks' ORDER BY ordinal_position"); }
        void exec(String sql) throws SQLException { try (var s = connection.createStatement()) { s.execute(sql); } }
        List<String> rows(String sql) throws SQLException {
            List<String> values = new ArrayList<>();
            try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { while (result.next()) values.add(result.getString(1)); }
            return values;
        }
        @Override public void close() throws SQLException { try { connection.rollback(); } finally { connection.close(); } }
    }

    private static String resource(String name) throws Exception {
        try (var in = RecordSelectiveIndexingDatabaseTest.class.getResourceAsStream("/db/" + name)) {
            assertNotNull(in, "missing migration resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
