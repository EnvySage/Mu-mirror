package org.xianshen.mumirrorb.tarot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.xianshen.mumirrorb.service.impl.TarotContextServiceImpl;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class TarotReferencesDatabaseTest {
    TarotExperienceDatabaseTest fixture=new TarotExperienceDatabaseTest();
    TarotContextServiceImpl context;
    static final Instant NOW=Instant.parse("2026-10-26T04:00:00Z");
    @BeforeAll static void schema() throws Exception {
        TarotExperienceDatabaseTest.database(); var db=TarotExperienceDatabaseTest.db;
        db.execute("CREATE TABLE IF NOT EXISTS records(id BIGSERIAL PRIMARY KEY,user_id UUID REFERENCES users(id) ON DELETE CASCADE,source TEXT,status TEXT,deleted_at TIMESTAMPTZ,created_at TIMESTAMPTZ,memory_complete BOOLEAN DEFAULT TRUE)");
        db.execute("CREATE TABLE IF NOT EXISTS chunks(id BIGSERIAL PRIMARY KEY,record_id BIGINT REFERENCES records(id) ON DELETE CASCADE,user_id UUID,segment TEXT,metadata JSONB,ai_memory_approved BOOLEAN,todo_approved BOOLEAN)");
        db.execute("CREATE OR REPLACE FUNCTION record_memory_is_complete(BIGINT) RETURNS BOOLEAN LANGUAGE SQL AS 'SELECT memory_complete FROM records WHERE id=$1'");
        db.execute("CREATE TABLE IF NOT EXISTS todo_registry(id BIGSERIAL PRIMARY KEY,user_id UUID REFERENCES users(id) ON DELETE CASCADE,title TEXT,notes TEXT,current_status TEXT,origin_kind TEXT,source_chunk_id BIGINT,deleted_at TIMESTAMPTZ,created_at TIMESTAMPTZ,version BIGINT DEFAULT 0)");
        db.execute("CREATE TABLE IF NOT EXISTS profile_snapshots(id BIGSERIAL PRIMARY KEY,user_id UUID REFERENCES users(id) ON DELETE CASCADE,snapshot_type TEXT,period_month TEXT,version_no INT,is_current BOOLEAN,invalidated BOOLEAN,overall_summary TEXT,mood_analysis TEXT,learning_analysis TEXT,todo_analysis TEXT,rhythm_analysis TEXT,created_at TIMESTAMPTZ)");
    }
    @BeforeEach void setup(){fixture.user();context=new TarotContextServiceImpl(TarotExperienceDatabaseTest.db);}
    @AfterEach void cleanup(){fixture.cleanup();}
    long diary(String source,int days,boolean approved){
        var db=TarotExperienceDatabaseTest.db;
        long id=db.queryForObject("INSERT INTO records(user_id,source,status,created_at) VALUES(?,?,'done',?) RETURNING id",Long.class,fixture.user,source,OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(days)),ZoneOffset.UTC));
        db.update("INSERT INTO chunks(record_id,user_id,segment,metadata,ai_memory_approved,todo_approved) VALUES(?,?,'我正在学习画画，希望坚持练习。','{\"title\":\"画画练习\"}'::jsonb,?,false)",id,fixture.user,approved);return id;
    }
    @Test void diaryWindowSwitchesAndAuthorizationAreHonored(){
        diary("user",1,true);diary("user",20,true);diary("user",1,false);diary("vault",1,true);
        assertEquals(1,context.build(fixture.user,"我想学习画画",new ReferenceOptions(true,7,false,false),NOW).materials().size());
        assertEquals(2,context.build(fixture.user,"我想学习画画",new ReferenceOptions(true,30,false,false),NOW).materials().size());
        assertTrue(context.build(fixture.user,"我想学习画画",new ReferenceOptions(false,30,false,false),NOW).materials().isEmpty());
        assertTrue(context.build(fixture.user,"",new ReferenceOptions(true,30,true,true),NOW).materials().isEmpty());
        var sources=context.build(fixture.user,"我想学习画画",new ReferenceOptions(true,7,false,false),NOW).materials();
        TarotExperienceDatabaseTest.db.update("UPDATE chunks SET ai_memory_approved=false WHERE user_id=?",fixture.user);
        assertFalse(context.validate(fixture.user,sources));
    }
    @Test void mirrorAndTodosStayIndependentOfDiaryAndChangesInvalidateAnalysis(){
        diary("user",1,true);var db=TarotExperienceDatabaseTest.db;
        db.update("INSERT INTO todo_registry(user_id,title,notes,current_status,origin_kind,created_at) VALUES(?,'学习画画','练习十分钟','not_started','direct',now())",fixture.user);
        db.update("INSERT INTO profile_snapshots(user_id,snapshot_type,version_no,is_current,invalidated,overall_summary,created_at) VALUES(?,'manual',1,true,false,'你最近在学习画画。',now())",fixture.user);
        var sources=context.build(fixture.user,"我想学习画画",new ReferenceOptions(false,7,true,true),NOW).materials();
        assertEquals(2,sources.size());assertTrue(sources.stream().noneMatch(s->s.kind().equals("diary")));
        assertTrue(context.validate(fixture.user,sources));
        db.update("UPDATE todo_registry SET current_status='completed',version=version+1 WHERE user_id=?",fixture.user);
        assertFalse(context.validate(fixture.user,sources));
        db.update("UPDATE profile_snapshots SET invalidated=true WHERE user_id=?",fixture.user);
        assertTrue(context.build(fixture.user,"画画",new ReferenceOptions(false,7,true,true),NOW).materials().isEmpty());
    }
}
