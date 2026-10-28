package org.xianshen.mumirrorb.tarot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.xianshen.mumirrorb.grpc.TarotGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.TarotProto;
import org.xianshen.mumirrorb.service.impl.*;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class TarotAnalysisExperienceTest {
    final TarotExperienceDatabaseTest fixture=new TarotExperienceDatabaseTest();
    TarotServiceImpl tarot; TarotAnalysisServiceImpl service; TarotGrpcClient model;
    final List<Runnable> jobs=new ArrayList<>();
    @BeforeAll static void database() throws Exception { TarotReferencesDatabaseTest.schema(); }
    @BeforeEach void setup(){
        fixture.user();tarot=fixture.at("2026-10-26T04:00:00Z");model=mock(TarotGrpcClient.class);
        service=new TarotAnalysisServiceImpl(TarotExperienceDatabaseTest.db,new ObjectMapper().findAndRegisterModules(),tarot,
            new TarotContextServiceImpl(TarotExperienceDatabaseTest.db),TarotExperienceDatabaseTest.catalog,model,TarotExperienceDatabaseTest.transactions,jobs::add);
    }
    @AfterEach void cleanup(){fixture.cleanup();}
    AnalyzeInput input(UUID id){return new AnalyzeInput(id,"我想试着学画画",new ReferenceOptions(false,7,false,false));}
    @Test void refreshAndRetryRecoverOneAnalysisWithoutChangingCard(){
        var reading=tarot.createReading(fixture.user,UUID.randomUUID());var input=input(UUID.randomUUID());
        when(model.analyze(eq(fixture.user),any())).thenReturn(TarotProto.TarotAnalyzeResponse.newBuilder().setText("先试一次简单速写，允许自己慢慢学。").build());
        var first=service.submit(fixture.user,reading.readingId(),input);
        assertEquals(first.analysisId(),service.submit(fixture.user,reading.readingId(),input).analysisId());assertEquals(1,jobs.size());
        jobs.remove(0).run();assertEquals("completed",service.get(fixture.user,first.analysisId()).status());
        assertEquals(reading.cardId(),tarot.getReading(fixture.user,reading.readingId()).cardId());
        verify(model,times(1)).analyze(eq(fixture.user),any());assertEquals(0,tarot.collection(fixture.user).unlockedCount());
        assertEquals(404,assertThrows(BusinessException.class,()->service.get(UUID.randomUUID(),first.analysisId())).getCode());
    }
    @Test void failedAndExpiredAnalysisCanRetryOnTheSameCard(){
        var reading=tarot.createReading(fixture.user,UUID.randomUUID());
        when(model.analyze(eq(fixture.user),any())).thenThrow(new RuntimeException("upstream failed"));
        var first=service.submit(fixture.user,reading.readingId(),input(UUID.randomUUID()));jobs.remove(0).run();
        assertEquals("failed",service.get(fixture.user,first.analysisId()).status());
        var second=service.submit(fixture.user,reading.readingId(),input(UUID.randomUUID()));
        TarotExperienceDatabaseTest.db.update("UPDATE tarot_analysis SET expires_at=now()-interval '1 second' WHERE id=?",second.analysisId());
        assertEquals("failed",service.get(fixture.user,second.analysisId()).status());jobs.remove(0).run();
        assertEquals("failed",service.get(fixture.user,second.analysisId()).status());
        assertEquals(reading.cardId(),tarot.getReading(fixture.user,reading.readingId()).cardId());
    }
}
