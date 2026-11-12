package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.impl.ChunkServiceImpl;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xianshen.mumirrorb.service.AchievementBusinessIntegrationTest.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementTagIntegrationTest {
    private final AchievementBusinessIntegrationTest fixture=new AchievementBusinessIntegrationTest();
    @BeforeAll static void open() throws Exception {AchievementBusinessIntegrationTest.database();}
    @AfterAll static void close() {AchievementBusinessIntegrationTest.cleanup();}

    @Test void personalTagEditCanBeReadFilteredAndValidatedAsTheSameRecord() throws Exception {
        fixture.account(); UUID user=fixture.user;
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);config.addMapper(RecordMapper.class);config.addMapper(ChunkMapper.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);factory.setConfiguration(config);
        factory.setTypeHandlers(new org.xianshen.mumirrorb.common.handler.UuidTypeHandler());
        var sessions=new org.mybatis.spring.SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var records=sessions.getMapper(RecordMapper.class);var chunks=sessions.getMapper(ChunkMapper.class);
        long record=db.queryForObject("INSERT INTO records(user_id,content,status,created_at) VALUES(?,'entry','record_only','2026-11-11T12:00:00+08:00') RETURNING id",Long.class,user);
        long chunk=db.queryForObject("""
            INSERT INTO chunks(user_id,record_id,content,segment,metadata)
            VALUES(?,?,'entry','entry','{"userTags":[],"user_tags":["old alias"],"tags":["stale alias"]}') RETURNING id
            """,Long.class,user,record);
        var editor=new ChunkServiceImpl(chunks,records,null,null,null);
        var change=new ChunkDTO();change.setUserTags(List.of("  new tag  ","new tag"));
        editor.update(chunk,change,user);
        var persisted=chunks.selectById(chunk).getMetadata();
        assertEquals(List.of("new tag"),persisted.get("userTags"));
        assertFalse(persisted.containsKey("tags"));assertFalse(persisted.containsKey("user_tags"));
        var browser=new RecordServiceImpl(records,chunks,null,null,null,null,null);
        var query=new RecordQueryDTO();query.setStartDate(LocalDate.of(2026,11,11));query.setTags(List.of("new tag"));
        assertEquals(List.of(record),browser.list(query,user).stream().map(RecordVO::getId).toList());
        activity.report(user,"record.filtered",Long.toString(record),"new tag","tag");
        assertTrue(fixture.earned("S-06"));
        query.setTags(List.of("stale alias"));assertTrue(browser.list(query,user).isEmpty());
    }
}
