package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.tools.ToolExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class LegacyReadRecordDatabaseTest {
    @Test void fullReadPaginatesAuthorizedSegmentsAndKeepsTailFacts() throws Exception {
        try(var f=RecordSelectiveIndexingDatabaseTest.Fixture.open(true)) {
            f.seedUsers();
            f.exec("""
                INSERT INTO records(id,user_id,content,source,status,user_reviewed,ai_processing_authorized,memory_state,index_policy_version)
                VALUES
                  (1,'00000000-0000-0000-0000-000000000001','raw secret','user','done',true,true,'READY',1),
                  (2,'00000000-0000-0000-0000-000000000001','old reviewing but indexed','user','reviewing',true,true,'READY',0),
                  (3,'00000000-0000-0000-0000-000000000001','old done and indexed','user','done',true,true,'READY',0);
                INSERT INTO chunks(id,record_id,user_id,content,segment,metadata,ai_memory_approved,embedding_enabled,embedding_status)
                SELECT n,1,'00000000-0000-0000-0000-000000000001','raw secret',CASE WHEN n=21 THEN repeat('x',150)||'TAIL_FACT' ELSE 'part '||n END,'{}',true,false,'not_requested' FROM generate_series(1,21) n;
                INSERT INTO chunks(id,record_id,user_id,content,segment,metadata,ai_memory_approved,embedding_enabled,embedding_status)
                VALUES(22,1,'00000000-0000-0000-0000-000000000001','secret','private secret','{}',false,false,'not_requested'),
                (23,1,'00000000-0000-0000-0000-000000000002','other','other user','{}',true,false,'not_requested'),
                (24,2,'00000000-0000-0000-0000-000000000001','old reviewing vector','reviewing secret','{}',true,true,'ready'),
                (25,3,'00000000-0000-0000-0000-000000000001','old done vector','old done authorized','{}',true,true,'ready');
                UPDATE chunks SET embedding=array_fill(0::real,ARRAY[1024])::vector WHERE id IN (24,25);
                """);
            var cfg=new MybatisConfiguration(); cfg.setMapUnderscoreToCamelCase(true);
            cfg.setEnvironment(new Environment("fixture",new JdbcTransactionFactory(),new SingleConnectionDataSource(f.connection,true)));
            cfg.addMapper(ChatSearchMapper.class);
            var session=new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(f.connection);
            var mapper=session.getMapper(ChatSearchMapper.class);
            var type=assertDoesNotThrow(()->Class.forName("org.xianshen.mumirrorb.tools.impl.ReadRecordTool"));
            var reader=(ToolExecutor)type.getConstructor(ChatSearchMapper.class).newInstance(mapper);
            var user=UUID.fromString("00000000-0000-0000-0000-000000000001");
            @SuppressWarnings("unchecked") var page=(Map<String,Object>)reader.execute(user,Map.of("record_id",1)).getPayload();
            assertEquals("partial",page.get("content_scope")); assertEquals(20L,page.get("next_offset"));
            assertEquals(20,((List<?>)page.get("snippets")).size());
            @SuppressWarnings("unchecked") var last=(Map<String,Object>)reader.execute(user,Map.of("record_id",1,"offset",20)).getPayload();
            assertEquals("full",last.get("content_scope")); assertNull(last.get("next_offset"));
            @SuppressWarnings("unchecked") var snippets=(List<Map<String,Object>>)last.get("snippets");
            assertEquals(1,snippets.size()); assertEquals(21L,snippets.get(0).get("chunk_id"));
            assertTrue(((String)snippets.get(0).get("text")).endsWith("TAIL_FACT"));
            @SuppressWarnings("unchecked") var other=(Map<String,Object>)reader.execute(UUID.fromString("00000000-0000-0000-0000-000000000002"),Map.of("record_id",1)).getPayload();
            assertEquals(false,other.get("body_available")); assertTrue(((List<?>)other.get("snippets")).isEmpty());
            @SuppressWarnings("unchecked") var reviewing=(Map<String,Object>)reader.execute(user,Map.of("record_id",2)).getPayload();
            assertEquals(false,reviewing.get("body_available")); assertTrue(((List<?>)reviewing.get("snippets")).isEmpty());
            @SuppressWarnings("unchecked") var oldDone=(Map<String,Object>)reader.execute(user,Map.of("record_id",3)).getPayload();
            assertEquals(true,oldDone.get("body_available"));
            @SuppressWarnings("unchecked") var oldDoneSnippets=(List<Map<String,Object>>)oldDone.get("snippets");
            assertEquals("old done authorized",oldDoneSnippets.get(0).get("text"));
        }
    }
}
