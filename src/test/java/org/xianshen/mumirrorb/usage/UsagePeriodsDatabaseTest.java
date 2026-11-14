package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.xianshen.mumirrorb.common.exception.GlobalExceptionHandler;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Exercises the HTTP binding and real receipt SQL together, including period-preserving pagination. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
class UsagePeriodsDatabaseTest extends UsageDatabaseFixture {
    MockMvc mvc;
    final ObjectMapper json=new ObjectMapper();
    LocalDate today,monthStart;
    UUID todayFirst,todayLast;
    @BeforeEach void receiptsAndApi() {
        today=LocalDate.now(UsageQueries.ZONE);monthStart=today.withDayOfMonth(1);
        // On the first day of a month the second day is still inside the natural month period.
        LocalDate otherDay=monthStart.equals(today)?monthStart.plusDays(1):monthStart;
        seed(monthStart.atStartOfDay(UsageQueries.ZONE).minusNanos(1000),1);
        seed(otherDay.atStartOfDay(UsageQueries.ZONE),2);
        seed(today.atStartOfDay(UsageQueries.ZONE).minusNanos(1000),4);
        todayFirst=seed(today.atStartOfDay(UsageQueries.ZONE),8);
        todayLast=seed(today.plusDays(1).atStartOfDay(UsageQueries.ZONE).minusNanos(1000),16);
        seed(monthStart.plusMonths(1).atStartOfDay(UsageQueries.ZONE),32);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),"fixture"));
        mvc=MockMvcBuilders.standaloneSetup(new UsageController(queries,store))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clearIdentity() {SecurityContextHolder.clearContext();}
    UUID seed(ZonedDateTime time,long tokens) {
        var e=envelope(UUID.randomUUID(),true,"a",time.toOffsetDateTime().toString());
        store.begin(e);store.complete(e,report(e.id(),UUID.randomUUID().toString(),Long.toString(tokens),"null",true),"success");
        return e.taskId();
    }
    JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    JsonNode tasks(String period) throws Exception {return request(get("/ai-usage/tasks").param("period",period)).path("data");}
    JsonNode summary(String period) throws Exception {return request(get("/ai-usage/summary").param("period",period)).path("data");}

    @Test void todayMonthAndAllTaskPeriodsMatchSummaryAcrossShanghaiBoundaries() throws Exception {
        assertEquals(2,tasks("today").path("items").size());
        assertEquals(2,summary("today").path("taskCount").asInt());
        assertEquals(24,summary("today").path("totalTokens").asInt());
        assertEquals(today.getDayOfMonth()==1?3:4,tasks("month").path("items").size());
        assertEquals(today.getDayOfMonth()==1?3:4,summary("month").path("taskCount").asInt());
        assertEquals(today.getDayOfMonth()==1?26:30,summary("month").path("totalTokens").asInt());
        assertEquals(6,tasks("all").path("items").size());
        assertEquals(6,summary("all").path("taskCount").asInt());
        assertEquals(63,summary("all").path("totalTokens").asInt());
        assertEquals(6,request(get("/ai-usage/tasks")).path("data").path("items").size(),"recent polling omits period and retains all history");
    }
    @Test void paginatedTasksKeepPeriodAndCustomDatesOverrideIt() throws Exception {
        JsonNode first=request(get("/ai-usage/tasks").param("period","today").param("limit","1")).path("data");
        assertEquals(todayLast.toString(),first.path("items").get(0).path("id").asText());
        String cursor=first.path("nextCursor").asText();assertFalse(cursor.isBlank());
        JsonNode second=request(get("/ai-usage/tasks").param("period","today").param("limit","1").param("before",cursor)).path("data");
        assertEquals(todayFirst.toString(),second.path("items").get(0).path("id").asText());
        assertTrue(second.path("nextCursor").isNull(),"the next-month task cannot escape the requested period");
        for(String endpoint:List.of("tasks","summary")) {
            JsonNode overridden=request(get("/ai-usage/"+endpoint).param("period","invalid-but-overridden")
                    .param("from",today.toString()).param("to",today.toString()));
            assertEquals(200,overridden.path("code").asInt());
            assertEquals(2,endpoint.equals("tasks")?overridden.path("data").path("items").size():overridden.path("data").path("taskCount").asInt());
        }
    }
    @Test void invalidPeriodsAndIncompleteDateRangesFailEquallyWithoutAffectingOwnerIsolation() throws Exception {
        for(String endpoint:List.of("tasks","summary")) {
            assertEquals(400,request(get("/ai-usage/"+endpoint).param("period","week")).path("code").asInt());
            assertEquals(400,request(get("/ai-usage/"+endpoint).param("period","today").param("from",today.toString())).path("code").asInt());
        }
        UUID outsider=UUID.randomUUID();db.update("insert into users(id) values(?)",outsider);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(outsider.toString(),"fixture"));
        assertEquals(0,tasks("today").path("items").size());
        assertEquals(0,summary("today").path("taskCount").asInt());
        assertEquals(6,((List<?>)queries.tasks(user,30,null,null,null,null,null,null).get("items")).size(),"old internal calls retain all-history default");
    }
}
