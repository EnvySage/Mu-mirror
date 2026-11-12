package org.xianshen.mumirrorb.achievement;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xianshen.mumirrorb.achievement.AchievementDatabaseTest.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementMemoryEvidenceDatabaseTest {
    private final AchievementDatabaseTest fixture=new AchievementDatabaseTest();
    private UUID user;
    @BeforeAll static void open() throws Exception {AchievementDatabaseTest.initialize();}
    @AfterAll static void close() {AchievementDatabaseTest.cleanup();}
    @BeforeEach void account() {fixture.account();user=fixture.user;}
    private String draft(UUID id,String text,boolean memory) {
        return "{\"recordDraft\":{\"segments\":[{\"draftId\":\""+id+"\",\"text\":\""+text+"\",\"title\":\"AI title\",\"memoryEnabled\":"+memory+"}]}}";
    }
    private UUID action(UUID draft,String text,String preparation) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO assistant_actions(id,user_id,kind,prepare_key,state,preparation_status,payload,preview,target_versions)
            VALUES(?,?,'record.create',?,'PENDING',?,?::jsonb,'{}','{}')
            """,id,user,id.toString(),preparation,draft(draft,text,false));
        return id;
    }
    private long chunk(String text,String metadata) {
        long r=db.queryForObject("INSERT INTO records(user_id,content,status,memory_state,user_reviewed) VALUES(?,'source','done','READY',true) RETURNING id",Long.class,user);
        return db.queryForObject("""
            INSERT INTO chunks(user_id,record_id,content,segment,classified_segment,metadata,user_edited,embedding_status,embedding)
            VALUES(?,?,'source',?,?,?::jsonb,true,'ready',?::vector) RETURNING id
            """,Long.class,user,r,text,text,metadata,VECTOR);
    }
    private long facts(String kind) {return db.queryForObject("SELECT count(*) FROM user_achievement_activity WHERE user_id=? AND kind=?",Long.class,user,kind);}
    private void approve(long id) {db.update("UPDATE chunks SET ai_memory_approved=true WHERE id=?",id);fixture.reconcile();}

    @Test void userEditedFlagOnAnUntouchedAiCandidateIsNotProofOfPersonalRevision() {
        long id=chunk("AI draft","{\"title\":\"AI title\"}");
        approve(id);
        assertEquals(0,facts("memory.edited"));assertEquals(0,facts("memory.reviewed"));assertEquals(0,fixture.earned("K-07"));
    }

    @Test void existingCandidateNeedsActualContentOrMetadataChangeThenConfirmation() {
        long id=chunk("AI draft","{\"title\":\"AI title\"}");
        db.update("UPDATE chunks SET segment='My corrected draft',classified_segment='My corrected draft' WHERE id=?",id);
        assertEquals(1,facts("memory.edited"));assertEquals(0,facts("memory.reviewed"));
        approve(id);
        assertEquals(1,facts("memory.reviewed"));assertEquals(1,fixture.earned("K-07"));
    }

    @Test void asynchronousPreparationDoesNotPretendToBeAUserEdit() {
        UUID draft=UUID.randomUUID(),id=action(draft,"raw input","RUNNING");
        db.update("UPDATE assistant_actions SET payload=?::jsonb,preparation_status='READY' WHERE id=?",draft(draft,"AI classified text",false),id);
        assertEquals(0,facts("memory.draft_edited"));
        long chunk=chunk("AI classified text","{}");
        db.update("UPDATE assistant_actions SET state='EXECUTING',execution_version=1,request_id='test' WHERE id=?",id);
        activities.bindDraftEdit(user,id,draft,chunk);approve(chunk);
        assertEquals(0,fixture.earned("K-07"));
    }

    @Test void ApprovalCheckboxAndRenamedDraftIdentityDoNotCreateEditEvidence() {
        UUID draft=UUID.randomUUID(),id=action(draft,"AI draft","READY");
        db.update("UPDATE assistant_actions SET payload=?::jsonb WHERE id=?",draft(draft,"AI draft",true),id);
        assertEquals(0,facts("memory.draft_edited"));
        db.update("UPDATE assistant_actions SET payload=?::jsonb WHERE id=?",draft(UUID.randomUUID(),"AI draft",true),id);
        assertEquals(0,facts("memory.draft_edited"));
    }

    @Test void actualPendingDraftRevisionBindsOnlyToItsOwnedExecutingActionAndNewChunk() {
        UUID draft=UUID.randomUUID(),id=action(draft,"AI draft","READY");
        db.update("UPDATE assistant_actions SET payload=?::jsonb WHERE id=?",draft(draft,"My corrected draft",true),id);
        assertEquals(1,facts("memory.draft_edited"));
        long chunk=chunk("My corrected draft","{}");
        activities.bindDraftEdit(user,id,draft,chunk);assertEquals(0,facts("memory.edited"));
        db.update("UPDATE assistant_actions SET state='EXECUTING',execution_version=1,request_id='test' WHERE id=?",id);
        activities.bindDraftEdit(fixture.otherAccount(),id,draft,chunk);assertEquals(0,facts("memory.edited"));
        activities.bindDraftEdit(user,id,draft,chunk);activities.bindDraftEdit(user,id,draft,chunk);
        assertEquals(1,facts("memory.edited"));approve(chunk);
        assertEquals(1,fixture.earned("K-07"));
    }

    @Test void newPersonalTagsAreRecordedWhileAiKeywordsAreNotTags() {
        chunk("source","{\"keywords\":[\"machine keyword\"]}");assertEquals(0,facts("record.tagged"));
        chunk("source","{\"userTags\":[\"my tag\"],\"keywords\":[\"machine keyword\"]}");
        assertEquals(1,facts("record.tagged"));
    }
}
