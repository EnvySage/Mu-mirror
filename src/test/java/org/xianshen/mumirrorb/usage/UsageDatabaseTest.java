package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
class UsageDatabaseTest extends UsageDatabaseFixture {
    @Test void receiptsSurviveBusinessRollbackAndReplayAndGroupedPhasesNeverDoubleCount() {
        UUID task=UUID.randomUUID(); var one=envelope(task,false,"a","2026-11-14T10:00:00+08:00");
        var two=envelope(task,false,"a","2026-11-14T10:00:01+08:00");
        String attempt=UUID.randomUUID().toString();
        tx.executeWithoutResult(status->{
            store.begin(one);store.complete(one,report(one.id(),attempt,"50","\"0.1\"",true),"success");
            store.begin(two);store.complete(two,report(two.id(),attempt,"20","\"0.2\"",true),"success");
            status.setRollbackOnly();
        });
        assertEquals("running",queries.detail(user,task).get("status"));
        store.finishTask(user,task);
        store.complete(one,report(one.id(),attempt,"999","\"100\"",true),"success");
        Map<String,Object> s=summary(null,null);
        assertEquals(70L,((Number)s.get("totalTokens")).longValue());
        assertEquals(2L,((Number)s.get("requestCount")).longValue());
        assertEquals(1L,((Number)s.get("taskCount")).longValue());
        assertEquals("0.3",((List<Map<String,Object>>)s.get("credits")).get(0).get("amount"));
        assertEquals("complete",queries.detail(user,task).get("status"));
    }
    @Test void userIsolationDateEdgesAndDecimalChannelsAreServerAggregated() {
        var before=envelope(UUID.randomUUID(),true,"a","2026-11-13T15:59:59Z");
        var after=envelope(UUID.randomUUID(),true,"b","2026-11-13T16:00:00Z");
        for(var e:List.of(before,after)) {store.begin(e);store.complete(e,report(e.id(),UUID.randomUUID().toString(),"50","\"0.100000000001\"",true),"success");}
        assertEquals(50L,((Number)summary("2026-11-14","2026-11-14").get("totalTokens")).longValue());
        assertEquals(2,((List<?>)summary(null,null).get("credits")).size());
        UUID stranger=UUID.randomUUID();db.update("insert into users(id) values(?)",stranger);
        assertNull(queries.detail(stranger,after.taskId()));
        assertTrue(((List<?>)queries.tasks(stranger,30,null,null,null,null,null,null).get("items")).isEmpty());
        assertEquals(0L,((Number)queries.summary(stranger,"all",null,null,null,null,null).get("requestCount")).longValue());
        assertThrows(RuntimeException.class,()->queries.summary(user,"all","2026-11-15","2026-11-14",null,null,null));
        assertThrows(RuntimeException.class,()->queries.tasks(user,30,"malformed",null,null,null,null,null));
    }
    @Test void missingTrailersDoNotInventRequestsAndStalePendingBecomesIncomplete() {
        var e=envelope(UUID.randomUUID(),true,"a","2026-11-14T00:00:00Z");
        store.begin(e);store.complete(e,new UsageReport(false,List.of()),"cancelled");
        var stale=envelope(UUID.randomUUID(),false,"a","2020-01-01T00:00:00Z");store.begin(stale);store.expirePending();
        Map<String,Object> s=summary(null,null);
        assertEquals(0L,((Number)s.get("requestCount")).longValue());
        assertEquals(0L,((Number)s.get("unknownCount")).longValue());
        assertEquals(2L,((Number)s.get("incompleteCount")).longValue());
        assertEquals(0L,((Number)s.get("activeCount")).longValue());
        assertEquals("cancelled",queries.detail(user,e.taskId()).get("status"));
    }
    @Test void emptyCompleteMeansNoUpstreamCallAndPagesUseStableCursor() {
        for(int i=0;i<3;i++) {
            var e=envelope(UUID.randomUUID(),true,"a","2026-11-14T00:00:00Z");store.begin(e);store.complete(e,new UsageReport(true,List.of()),"success");
        }
        var first=queries.tasks(user,2,null,null,null,null,null,null);
        assertEquals(2,((List<?>)first.get("items")).size());
        var second=queries.tasks(user,2,(String)first.get("nextCursor"),null,null,null,null,null);
        assertEquals(1,((List<?>)second.get("items")).size());
        assertEquals(0L,((Number)summary(null,null).get("incompleteCount")).longValue());
        assertEquals(0L,((Number)summary(null,null).get("requestCount")).longValue());
    }
}

