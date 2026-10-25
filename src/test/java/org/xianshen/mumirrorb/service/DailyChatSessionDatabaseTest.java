package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.action.ActionHandler;
import org.xianshen.mumirrorb.action.ActionExecution;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only MIRROR_TEST_DB_URL, an explicitly configured disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
@SpringBootTest(classes = DailyChatSessionDatabaseTest.TestApplication.class, properties = {
        "spring.main.web-application-type=none",
        "spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource"
})
@ActiveProfiles("test")
class DailyChatSessionDatabaseTest {
    @Autowired private DailyChatSessionResolver resolver;
    @Autowired private ChatSessionMapper sessions;
    @Autowired private ConversationHistoryMapper history;
    @Autowired private ActionProposalMapper actionMapper;
    @Autowired private ActionProposalService actions;
    @Autowired private JdbcTemplate jdbc;
    private UUID userId;

    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MIRROR_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("MIRROR_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MIRROR_TEST_DB_PASSWORD"));
    }

    @BeforeEach void createUser() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,password_hash) VALUES (?,?,?)", userId, "daily-" + userId, "test-only");
    }

    @AfterEach void removeUser() {
        if (userId != null) {
            jdbc.update("DELETE FROM assistant_actions WHERE user_id=?", userId);
            jdbc.update("DELETE FROM chat_sessions WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM assistant_actions WHERE user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_day_summaries WHERE user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_memories WHERE user_id=?", Integer.class, userId));
        }
    }

    @Test void sameDayTwoClientsResolveSameSession() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 19);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            var a = clients.submit(() -> resolver.getOrCreate(userId, day, "client A"));
            var b = clients.submit(() -> resolver.getOrCreate(userId, day, "client B"));
            ChatSession first = a.get(10, TimeUnit.SECONDS);
            ChatSession second = b.get(10, TimeUnit.SECONDS);
            assertEquals(first.getId(), second.getId());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chat_sessions WHERE user_id=? AND day_key=?", Integer.class, userId, day));
            assertEquals("Asia/Shanghai", first.getTimezone());
        } finally { clients.shutdownNow(); }
    }

    @Test void streamAcrossMidnightFinishesInOriginalSession() {
        LocalDate day1 = LocalDate.of(2026, 10, 19);
        ChatSession original = resolver.getOrCreate(userId, day1, "before midnight");
        UUID capturedStreamSession = original.getId();
        ChatSession afterMidnight = resolver.resolveSend(userId, original.getId(), "next message", day1.plusDays(1));
        assertEquals(original.getId(), capturedStreamSession);
        assertNotEquals(original.getId(), afterMidnight.getId());
        assertEquals(day1, sessions.selectById(original.getId()).getDayKey());
        assertEquals(day1.plusDays(1), afterMidnight.getDayKey());
    }

    @Test void weekendReturnKeepsOldSessionReadableAndAssistantMessagesOutOfCandidateSources() {
        LocalDate friday = LocalDate.of(2026, 10, 16);
        ChatSession oldDay = resolver.getOrCreate(userId, friday, "Friday");
        Long userMessage = jdbc.queryForObject("INSERT INTO conversation_history(session_id,user_id,role,content) VALUES (?,?,?,?) RETURNING id",
                Long.class, oldDay.getId(), userId, "user", "周末准备读书");
        jdbc.update("INSERT INTO conversation_history(session_id,user_id,role,content) VALUES (?,?,?,?)",
                oldDay.getId(), userId, "assistant", "可以选一本轻松的书");

        ChatSession sundaySession = resolver.resolveSend(userId, oldDay.getId(), "周日回来", friday.plusDays(2));

        assertNotEquals(oldDay.getId(), sundaySession.getId());
        assertEquals(oldDay.getId(), sessions.selectById(oldDay.getId()).getId());
        assertEquals(2, history.selectBySession(oldDay.getId(), userId).size());
        assertEquals(List.of(userMessage), history.selectUserMessages(oldDay.getId(), userId).stream().map(row -> row.getId()).toList());
    }

    @Test void legacySessionRemainsUnbackfilledButNewSendUsesToday() {
        ChatSession legacy = ChatSession.builder().id(UUID.randomUUID()).userId(userId).title("legacy")
                .dayKey(null).timezone(null).build();
        sessions.insert(legacy);
        LocalDate today = LocalDate.of(2026, 10, 19);

        ChatSession resolved = resolver.resolveSend(userId, legacy.getId(), "new turn", today);

        assertNotEquals(legacy.getId(), resolved.getId());
        assertNull(sessions.selectById(legacy.getId()).getDayKey());
        assertEquals(today, resolved.getDayKey());
    }

    @Test void concurrentMemoryCandidatePreparationSerializesAtThreeAndRetriesIdempotently() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 19);
        ChatSession session = resolver.getOrCreate(userId, day, "memory candidates");
        Long sourceId = jdbc.queryForObject("INSERT INTO conversation_history(session_id,user_id,role,content) VALUES (?,?,?,?) RETURNING id",
                Long.class, session.getId(), userId, "user", "source");
        var first = actions.prepare(userId, memoryRequest(session.getId(), sourceId, "memory-first"));
        var second = actions.prepare(userId, memoryRequest(session.getId(), sourceId, "memory-second"));
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            var a = clients.submit(() -> prepareSafely(memoryRequest(session.getId(), sourceId, "memory-third")));
            var b = clients.submit(() -> prepareSafely(memoryRequest(session.getId(), sourceId, "memory-fourth")));
            List<PreparationResult> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            List<String> states = results.stream().map(PreparationResult::state).sorted().toList();
            assertEquals(List.of("REJECTED", "SUCCEEDED"), states);
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM assistant_actions WHERE user_id=? AND source_session_id=? AND kind='memory.accept'",
                    Integer.class, userId, session.getId()));
            String winnerKey = results.stream().filter(result -> "SUCCEEDED".equals(result.state()))
                    .map(PreparationResult::key).findFirst().orElseThrow();
            UUID storedWinnerId = jdbc.queryForObject("SELECT id FROM assistant_actions WHERE user_id=? AND prepare_key=?",
                    UUID.class, userId, winnerKey);
            var retry = actions.prepare(userId, memoryRequest(session.getId(), sourceId, winnerKey));
            assertEquals(storedWinnerId, retry.getId());
        } finally { clients.shutdownNow(); }
    }

    private PreparationResult prepareSafely(ActionPrepareDTO body) {
        try { actions.prepare(userId, body); return new PreparationResult(body.getPrepareKey(), "SUCCEEDED"); }
        catch (RuntimeException rejected) { return new PreparationResult(body.getPrepareKey(), "REJECTED"); }
    }

    private record PreparationResult(String key, String state) { }

    private static ActionPrepareDTO memoryRequest(UUID sessionId, Long sourceId, String key) {
        return new ActionPrepareDTO("memory.accept", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .set("memoryDraft", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()), key, sessionId, sourceId);
    }

    @SpringBootConfiguration @EnableAutoConfiguration @MapperScan("org.xianshen.mumirrorb.mapper")
    @Import({DailyChatSessionResolver.class, org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl.class})
    static class TestApplication {
        @Bean ActionHandler memoryCandidateTestHandler() {
            return new ActionHandler() {
                private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                @Override public java.util.Set<String> kinds() { return java.util.Set.of("memory.accept"); }
                @Override public com.fasterxml.jackson.databind.JsonNode validateAndPreview(UUID userId, String kind,
                        com.fasterxml.jackson.databind.JsonNode payload) {
                    return json.createObjectNode().set("targetVersions", json.createObjectNode());
                }
                @Override public ActionExecution execute(org.xianshen.mumirrorb.action.AuthorizedAction action) {
                    return ActionExecution.applied(json.createObjectNode());
                }
            };
        }
    }
}
