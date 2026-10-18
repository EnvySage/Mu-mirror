package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.xianshen.mumirrorb.action.TodoActionHandler;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DTO.*;
import org.xianshen.mumirrorb.service.impl.*;
import org.xianshen.mumirrorb.tools.*;
import org.xianshen.mumirrorb.tools.impl.PrepareTodoTool;
import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicit disposable PostgreSQL only; no application YAML, model, or business datasource. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL", matches="^jdbc:postgresql:.*$")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoOperationsDatabaseTest {
    private final String schema = "todo_test_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper();
    private DriverManagerDataSource bootstrap, dataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TodoRegistryService todos;
    private ActionProposalService actions;
    private TodoRegistryMapper mapper;
    private ProfileStatsMapper stats;
    private DailySummaryMapper daily;
    private UUID owner, other, session;
    private long message;

    @Configuration @EnableTransactionManagement static class TxConfig {}

    @BeforeAll void initializeIsolatedSchema() throws Exception {
        bootstrap = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"), System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        try (Connection connection = bootstrap.getConnection(); Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_database()")) {
                assertTrue(result.next());
                assertTrue(result.getString(1).startsWith("codex_assistant_growth_test_"), "Refusing any non-test database");
            }
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"), System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        Properties properties = new Properties(); properties.setProperty("currentSchema", schema); dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE chat_sessions(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id))");
        jdbc.execute("CREATE TABLE conversation_history(id BIGSERIAL PRIMARY KEY, session_id UUID REFERENCES chat_sessions(id), user_id UUID NOT NULL REFERENCES users(id), role TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE records(id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), source TEXT NOT NULL DEFAULT 'user', status TEXT NOT NULL DEFAULT 'done', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE chunks(id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), record_id BIGINT REFERENCES records(id), segment TEXT, metadata JSONB DEFAULT '{}', todo_approved BOOLEAN DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE todo_registry(id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), title VARCHAR(100) NOT NULL, current_status VARCHAR(20) NOT NULL DEFAULT 'not_started', source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL, created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now(), closed_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE todo_registry_links(id BIGSERIAL PRIMARY KEY, todo_id BIGINT REFERENCES todo_registry(id), chunk_id BIGINT REFERENCES chunks(id) ON DELETE CASCADE, relation VARCHAR(10), created_at TIMESTAMPTZ DEFAULT now(), UNIQUE(todo_id,chunk_id))");
        jdbc.execute("CREATE TABLE todo_suggestions(id BIGSERIAL PRIMARY KEY, user_id UUID REFERENCES users(id), todo_id BIGINT REFERENCES todo_registry(id), evidence_chunk_id BIGINT REFERENCES chunks(id) ON DELETE CASCADE, suggested_status VARCHAR(20), status VARCHAR(20) DEFAULT 'pending', created_at TIMESTAMPTZ DEFAULT now(), resolved_at TIMESTAMPTZ)");
        // An existing NULL-source row must stay record-origin after migration.
        UUID legacy = UUID.randomUUID(); jdbc.update("INSERT INTO users VALUES (?)", legacy);
        jdbc.update("INSERT INTO todo_registry(user_id,title) VALUES (?, 'legacy orphan')", legacy);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration-v7-todo-direct-operations.sql"));
        migration.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
        migration.execute(dataSource); migration.execute(dataSource);
        assertEquals("record", jdbc.queryForObject("SELECT origin_kind FROM todo_registry WHERE user_id=?", String.class, legacy));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-v6-assistant-actions.sql")).execute(dataSource);
        // Actual SQL writes to any source chunk fail the test, including apparently harmless metadata writes.
        jdbc.execute("CREATE FUNCTION reject_chunk_updates() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'source chunks are read only'; END $$");
        jdbc.execute("CREATE TRIGGER source_read_only BEFORE UPDATE ON chunks FOR EACH ROW EXECUTE FUNCTION reject_chunk_updates()");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        for (Class<?> type : List.of(TodoRegistryMapper.class, TodoRegistryLinkMapper.class, TodoSuggestionMapper.class, ProfileStatsMapper.class, DailySummaryMapper.class, ActionProposalMapper.class)) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        var sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        mapper = sql.getMapper(TodoRegistryMapper.class); stats = sql.getMapper(ProfileStatsMapper.class); daily = sql.getMapper(DailySummaryMapper.class);
        context = new AnnotationConfigApplicationContext(); context.register(TxConfig.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(TodoRegistryServiceImpl.class, () -> new TodoRegistryServiceImpl(mapper, sql.getMapper(TodoRegistryLinkMapper.class), sql.getMapper(TodoSuggestionMapper.class), mock(ChunkMapper.class), mock(RecordMapper.class)));
        context.registerBean(TodoActionHandler.class, () -> new TodoActionHandler(context.getBean(TodoRegistryService.class), json));
        context.registerBean(ActionProposalServiceImpl.class, () -> new ActionProposalServiceImpl(sql.getMapper(ActionProposalMapper.class), List.of(context.getBean(TodoActionHandler.class))));
        context.refresh(); todos = context.getBean(TodoRegistryService.class); actions = context.getBean(ActionProposalService.class);
    }

    @AfterAll void dropOnlyThisSchema() throws Exception {
        if (context != null) context.close();
        if (dataSource != null) try (var connection = bootstrap.getConnection(); var statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @BeforeEach void fixture() {
        owner=UUID.randomUUID(); other=UUID.randomUUID(); session=UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?),(?)", owner, other);
        jdbc.update("INSERT INTO chat_sessions VALUES (?,?)", session, owner);
        message=jdbc.queryForObject("INSERT INTO conversation_history(session_id,user_id,role) VALUES (?,?,'user') RETURNING id", Long.class, session, owner);
    }

    private TodoCommandDTO command(String title, String status) {
        var body=new TodoCommandDTO(); body.setTitle(title); body.setCurrentStatus(status); body.setNotes("备注"); body.setDueAt(OffsetDateTime.parse("2026-10-20T15:00:00+08:00")); return body;
    }
    private long recordTodo(String title, UUID user, boolean approved) {
        long record=jdbc.queryForObject("INSERT INTO records(user_id) VALUES (?) RETURNING id", Long.class, user);
        long chunk=jdbc.queryForObject("INSERT INTO chunks(user_id,record_id,segment,metadata,todo_approved) VALUES (?,?,'只读历史正文','{\"taskStatus\":\"not_started\"}',?) RETURNING id", Long.class,user,record,approved);
        return jdbc.queryForObject("INSERT INTO todo_registry(user_id,title,source_chunk_id) VALUES (?,?,?) RETURNING id",Long.class,user,title,chunk);
    }
    private ActionPrepareDTO prepare(String kind, String payload, String key) throws Exception {
        return new ActionPrepareDTO(kind,json.readTree(payload),key,session,message);
    }
    private String target(long id,long version) { return "{\"todoId\":"+id+",\"expectedTargetVersion\":"+version+"}"; }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM todo_registry WHERE user_id=? AND deleted_at IS NULL",Integer.class,owner); }

    @Test void directAndRecordRowsHaveConsistentListHintStatsAndDailySemantics() {
        long direct=todos.createDirect(owner,command("独立待办","not_started")).getId();
        long record=recordTodo("来源待办",owner,true); long orphan=recordTodo("失去来源",owner,true);
        long source=jdbc.queryForObject("SELECT source_chunk_id FROM todo_registry WHERE id=?",Long.class,orphan); jdbc.update("DELETE FROM chunks WHERE id=?",source);
        long unapproved=recordTodo("未授权",owner,false); recordTodo("他人待办",other,true);
        var completed=todos.createDirect(owner,command("已完成","completed"));
        var deleted=todos.createDirect(owner,command("已删除","not_started")); todos.deleteTodo(deleted.getId(),owner,1);
        assertEquals(Set.of(direct,record),new HashSet<>(todos.openTodosForHint(owner).stream().map(TodoRegistryDTO.TodoItem::getTodoId).toList()));
        var list=todos.listAll(owner);
        assertFalse(list.stream().filter(t->t.getId()==direct).findFirst().orElseThrow().getOrphan());
        assertTrue(list.stream().filter(t->t.getId()==orphan).findFirst().orElseThrow().getOrphan());
        assertEquals("record",list.stream().filter(t->t.getId()==orphan).findFirst().orElseThrow().getOriginKind());
        assertEquals(2,todos.listOpenChains(owner).size());
        assertEquals(2,stats.selectOpenTodos(owner).size());
        assertEquals(3L,stats.selectTodoStatusCounts(owner).stream().mapToLong(m->((Number)m.get("count")).longValue()).sum());
        assertEquals(2,daily.selectOpenTodos(owner,OffsetDateTime.now().minusDays(1),OffsetDateTime.now().plusDays(1)).size());
        assertNotNull(mapper.selectOneByUser(direct,owner)); assertNull(mapper.selectOneByUser(direct,other));
        assertEquals(5,list.size()); // direct, record, orphan, unapproved, completed
    }

    @Test void optimisticUpdateAndDeleteRejectStaleAndForeignTargets() {
        var created=todos.createDirect(owner,command("before","not_started"));
        assertThrows(BusinessException.class,()->todos.updateDirect(other,created.getId(),1,command("foreign","completed")));
        var updated=todos.updateDirect(owner,created.getId(),1,command("after","in_progress")); assertEquals(2L,updated.getVersion());
        assertEquals(409,assertThrows(BusinessException.class,()->todos.updateDirect(owner,created.getId(),1,command("stale","completed"))).getCode());
        assertThrows(BusinessException.class,()->todos.deleteTodo(created.getId(),owner,1));
        assertThrows(BusinessException.class,()->todos.deleteTodo(created.getId(),other,2));
        todos.deleteTodo(created.getId(),owner,2); assertTrue(todos.listAll(owner).isEmpty());
        assertEquals(3L,jdbc.queryForObject("SELECT version FROM todo_registry WHERE id=?",Long.class,created.getId()));
        assertEquals(0,mapper.updateVersioned(owner,created.getId(),3,command("resurrect","not_started")));
    }

    @Test void patchPreservesOmittedFieldsAndCanExplicitlyClearNotesAndDueDate() throws Exception {
        var original=todos.createDirect(owner,command("original","in_progress"));
        var patch=json.readValue("{\"title\":\" changed \"}",TodoCommandDTO.class);
        var updated=todos.updateDirect(owner,original.getId(),1,patch);
        assertEquals("changed",updated.getTitle()); assertEquals("in_progress",updated.getCurrentStatus());
        assertEquals("备注",updated.getNotes()); assertNotNull(updated.getDueAt());
        var clear=json.readValue("{\"notes\":null,\"dueAt\":null}",TodoCommandDTO.class);
        var cleared=todos.updateDirect(owner,original.getId(),2,clear);
        assertNull(cleared.getNotes()); assertNull(cleared.getDueAt());
        assertEquals("changed",todos.listAll(owner).get(0).getTitle());
    }

    @Test void concurrentUpdatesWithSameVersionHaveOneWinner() throws Exception {
        long id=todos.createDirect(owner,command("before","not_started")).getId();
        var workers=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Boolean> update=()->{start.await();try {todos.updateDirect(owner,id,1,command("winner","in_progress"));return true;} catch(BusinessException conflict){assertEquals(409,conflict.getCode());return false;}};
            var a=workers.submit(update);var b=workers.submit(update);start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        } finally {workers.shutdownNow();}
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM todo_registry WHERE id=?",Long.class,id));
    }

    @Test void toolPreparationWritesZeroTodosAndConfirmedCardCreatesExactlyOnce() {
        var tool=new PrepareTodoTool(actions,todos,json);
        var args=Map.<String,Object>of("kind","todo.create","title","交材料","currentStatus","not_started");
        var callContext=new ToolCallContext(session,message,"trusted-call");
        var result=tool.execute(owner,args,callContext);assertEquals(0,count());
        var payload=(Map<?,?>)result.getPayload();UUID id=UUID.fromString((String)payload.get("proposalId"));
        assertEquals("PENDING",actions.get(owner,id).getState());
        actions.confirm(owner,id,new ActionConfirmDTO(1,"device-one"));
        actions.confirm(owner,id,new ActionConfirmDTO(1,"device-two"));
        assertEquals(1,count());assertEquals("SUCCEEDED",actions.get(owner,id).getState());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM records WHERE user_id=?",Integer.class,owner));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE user_id=?",Integer.class,owner));
        tool.execute(owner,args,callContext);assertEquals(1,count());
    }

    @Test void readonlyRecordTodoCanCompleteAndDeleteWithoutSourceMutation() throws Exception {
        long id=recordTodo("原任务",owner,true);
        var card=actions.prepare(owner,prepare("todo.complete",target(id,1),"complete"));
        assertEquals("not_started",todos.ownedActive(owner,id).getCurrentStatus());
        actions.confirm(owner,card.getId(),new ActionConfirmDTO(1,"apply"));
        assertEquals("completed",todos.ownedActive(owner,id).getCurrentStatus());
        assertEquals("not_started",jdbc.queryForObject("SELECT c.metadata->>'taskStatus' FROM chunks c JOIN todo_registry t ON t.source_chunk_id=c.id WHERE t.id=?",String.class,id));
        var deletion=actions.prepare(owner,prepare("todo.delete",target(id,2),"delete"));
        actions.confirm(owner,deletion.getId(),new ActionConfirmDTO(1,"delete"));
        assertEquals("只读历史正文",jdbc.queryForObject("SELECT c.segment FROM chunks c JOIN todo_registry t ON t.source_chunk_id=c.id WHERE t.id=?",String.class,id));
    }

    @Test void staleConfirmationPersistsStaleAndNeverAppliesOldPayload() throws Exception {
        long id=todos.createDirect(owner,command("before","not_started")).getId();
        var card=actions.prepare(owner,prepare("todo.complete",target(id,1),"stale"));
        todos.updateDirect(owner,id,1,command("changed","in_progress"));
        assertThrows(BusinessException.class,()->actions.confirm(owner,card.getId(),new ActionConfirmDTO(1,"stale")));
        assertEquals("STALE",actions.get(owner,card.getId()).getState());
        assertEquals("in_progress",todos.ownedActive(owner,id).getCurrentStatus());
        assertEquals(2L,todos.ownedActive(owner,id).getVersion());
    }

    @Test void deletedTargetMakesPreparedCardStaleAndCannotBeResurrected() throws Exception {
        long id=todos.createDirect(owner,command("before","not_started")).getId();
        var card=actions.prepare(owner,prepare("todo.complete",target(id,1),"deleted-target"));
        todos.deleteTodo(id,owner,1);
        assertThrows(BusinessException.class,()->actions.confirm(owner,card.getId(),new ActionConfirmDTO(1,"stale")));
        assertEquals("STALE",actions.get(owner,card.getId()).getState()); assertEquals(0,count());
        assertThrows(BusinessException.class,()->actions.prepare(owner,prepare("todo.complete",target(id,2),"new-deleted")));
    }

    @Test void preparationAndEditValidateExpectedVersionWhileConfirmRecomputesServerSnapshot() throws Exception {
        long id=todos.createDirect(owner,command("before","not_started")).getId();
        assertThrows(BusinessException.class,()->actions.prepare(owner,prepare("todo.complete",target(id,7),"wrong-version")));
        var card=actions.prepare(owner,prepare("todo.complete",target(id,1),"edit"));
        assertThrows(BusinessException.class,()->actions.edit(owner,card.getId(),new ActionEditDTO(1,json.readTree(target(id,9)))));
        assertEquals(1,actions.get(owner,card.getId()).getVersion());
        var edited=actions.edit(owner,card.getId(),new ActionEditDTO(1,json.readTree(target(id,1))));
        assertEquals(2,edited.getVersion());
        assertThrows(BusinessException.class,()->actions.confirm(owner,card.getId(),new ActionConfirmDTO(1,"old-card")));
        actions.confirm(owner,card.getId(),new ActionConfirmDTO(2,"edited-card"));
        assertEquals("completed",todos.ownedActive(owner,id).getCurrentStatus());
    }

    @Test void forgedPayloadAndForeignTargetsCannotPrepareAndCancelledKeyStaysCancelled() throws Exception {
        long foreign=recordTodo("foreign",other,true);
        assertThrows(BusinessException.class,()->actions.prepare(owner,prepare("todo.complete",target(foreign,1),"foreign")));
        assertThrows(BusinessException.class,()->actions.prepare(owner,prepare("todo.create","{\"title\":\"hack\",\"confirmed\":true}","forged")));
        assertThrows(BusinessException.class,()->actions.prepare(owner,prepare("todo.create","{\"title\":\"hack\",\"userId\":\""+other+"\"}","identity")));
        var body=prepare("todo.create","{\"title\":\"cancelled\"}","cancel");var card=actions.prepare(owner,body);
        actions.cancel(owner,card.getId(),1);assertEquals("CANCELLED",actions.prepare(owner,body).getState());assertEquals(0,count());
    }
}
