package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.xianshen.mumirrorb.achievement.*;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.controller.ExportController;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.VO.ExportVO;
import org.xianshen.mumirrorb.service.impl.SettingsServiceImpl;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL", matches="^jdbc:postgresql:.*$")
class AchievementBusinessIntegrationTest {
    static JdbcTemplate admin, db;
    static DriverManagerDataSource source;
    static String schema;
    static AchievementActivity activity;
    static AchievementService achievements;
    UUID user;

    @BeforeAll static void database() throws Exception {
        source = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),
                System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(source);
        assertTrue(admin.queryForObject("SELECT current_database()", String.class)
                .startsWith("codex_assistant_growth_test_"));
        schema = "achievement_business_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var properties = new Properties();
        properties.setProperty("currentSchema", schema + ",public");
        source.setConnectionProperties(properties);
        db = new JdbcTemplate(source);
        db.execute(new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8));
        var json = new ObjectMapper();
        var metrics = new AchievementMetrics(db, new TarotCatalog(json));
        achievements = new AchievementService(db, json, new AchievementCatalog(json, metrics), metrics);
        achievements.installCatalog();
        activity = new AchievementActivity(db);
    }

    @AfterAll static void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @BeforeEach void account() {
        user = UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,'test')", user, "hooks-" + user);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    boolean earned(String code) {
        @SuppressWarnings("unchecked")
        var items = (List<ObjectNode>) achievements.collection(user).get("items");
        return items.stream().filter(item -> code.equals(item.path("code").asText()))
                .findFirst().orElseThrow().path("earned").asBoolean();
    }

    @Test void successfulDownloadsRecordTheirActualFormats() throws Exception {
        var exports = mock(ExportService.class);
        when(exports.exportJson(user)).thenReturn(ExportVO.builder().version("v2")
                .exportedAt("2026-11-11T12:00:00+08:00").username("test").build());
        var controller = new ExportController(exports);
        ReflectionTestUtils.setField(controller, "achievementActivity", activity);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.toString(), "", List.of()));
        var json = new MockHttpServletResponse();
        controller.exportJson(json);
        assertTrue(json.getContentAsString().contains("exportedAt"));
        assertTrue(earned("S-03"));
        assertFalse(earned("S-07"));
        var markdown = new MockHttpServletResponse();
        controller.exportMarkdown(markdown);
        assertTrue(markdown.getContentAsString().contains("#"));
        assertTrue(earned("S-07"));
    }

    @Test void failedEmbeddingProbeCannotCompleteCalibration() {
        String encrypted = CryptoUtils.encrypt("only-a-test-key");
        OffsetDateTime revision = db.queryForObject("""
            INSERT INTO user_settings(user_id,ai_provider,ai_model,ai_api_key,embedding_source,embedding_model)
            VALUES(?,'test','test',?,'local','test') RETURNING updated_at
            """, OffsetDateTime.class, user, encrypted);
        var settings = UserSettings.builder().userId(user).aiProvider("test").aiModel("test")
                .aiApiKey(encrypted).embeddingSource("local").embeddingModel("test").updatedAt(revision).build();
        var mapper = mock(SettingsMapper.class);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(settings);
        var ai = mock(AiGrpcClient.class);
        when(ai.testLlmConnection(user)).thenReturn(RecordProcessorProto.ClassifyResponse.newBuilder()
                .addItems(RecordProcessorProto.ClassifyItem.newBuilder().setTitle("connected")).build());
        when(ai.getModelInfo(user)).thenReturn(EmbeddingProto.ModelInfoResponse.newBuilder()
                .setAvailable(true).setDimension(768).build());
        var service = new SettingsServiceImpl(mapper, ai);
        ReflectionTestUtils.setField(service, "achievementActivity", activity);
        service.testAiConnection(user);
        assertThrows(BusinessException.class, () -> service.testEmbeddingConnection(user));
        assertFalse(earned("M-05"));
        when(ai.getModelInfo(user)).thenReturn(EmbeddingProto.ModelInfoResponse.newBuilder()
                .setAvailable(true).setDimension(1024).build());
        service.testEmbeddingConnection(user);
        assertTrue(earned("M-05"));
    }

    @Test void extractedTextReviewExcludesOtherOwnersAndInternalChunks() {
        long item = db.queryForObject("""
            INSERT INTO vault_items(user_id,original_name,storage_key,size_bytes,mime,digest_status)
            VALUES(?,'review.txt','review',1,'text/plain','extracted') RETURNING id
            """, Long.class, user);
        long record = db.queryForObject("INSERT INTO records(user_id,content,status) VALUES(?,'file','record_only') RETURNING id", Long.class, user);
        db.update("""
            INSERT INTO chunks(user_id,record_id,vault_item_id,content,segment,metadata)
            VALUES(?,?,?,'text','read this','{}'),(?,?,?,'key','private key','{"keyChunk":true}')
            """, user, record, item, user, record, item);
        var review = new VaultReviewService(db);
        var result = review.chunks(user, item);
        assertEquals(1, result.size());
        assertEquals("read this", result.get(0).get("segment"));
        assertEquals(Set.of("id", "segment"), result.get(0).keySet());
        assertThrows(BusinessException.class, () -> review.chunks(UUID.randomUUID(), item));
        db.update("UPDATE vault_items SET digest_status='pending' WHERE id=?", item);
        assertThrows(BusinessException.class, () -> review.chunks(user, item));
        db.update("UPDATE vault_items SET deleted_at=now() WHERE id=?", item);
        assertThrows(BusinessException.class, () -> review.chunks(user, item));
    }

    @Test void recordSearchReallyFiltersMoodTypeAndTagsBeforeReturningResults() throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(org.xianshen.mumirrorb.mapper.RecordMapper.class);
        config.addMapper(org.xianshen.mumirrorb.mapper.ChunkMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setTypeHandlers(new org.xianshen.mumirrorb.common.handler.UuidTypeHandler());
        var sessions = new org.mybatis.spring.SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var service = new org.xianshen.mumirrorb.service.impl.RecordServiceImpl(
                sessions.getMapper(org.xianshen.mumirrorb.mapper.RecordMapper.class),
                sessions.getMapper(org.xianshen.mumirrorb.mapper.ChunkMapper.class), null, null, null, null, null);
        List<Long> ids = new ArrayList<>();
        for (String metadata : List.of("{\"mood\":[\"开心\"],\"contentType\":\"life\",\"userTags\":[\"旅行\"]}",
                "{\"mood\":\"开心\",\"contentType\":\"study\"}",
                "{\"mood\":[\"疲惫\"],\"contentType\":\"life\"}")) {
            long id = db.queryForObject("INSERT INTO records(user_id,content,status,created_at) VALUES(?,'filter','record_only','2026-11-11T12:00:00+08:00') RETURNING id", Long.class, user);
            ids.add(id);
            db.update("INSERT INTO chunks(user_id,record_id,content,segment,metadata) VALUES(?,?,'filter','filter',?::jsonb)", user, id, metadata);
        }
        var query = new org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO();
        query.setStartDate(java.time.LocalDate.of(2026, 11, 11));
        query.setMood("开心");
        assertEquals(Set.of(ids.get(0), ids.get(1)), service.list(query, user).stream()
                .map(org.xianshen.mumirrorb.pojo.VO.RecordVO::getId).collect(java.util.stream.Collectors.toSet()));
        query.setContentType("life");
        query.setTags(List.of("旅行"));
        assertEquals(List.of(ids.get(0)), service.list(query, user).stream()
                .map(org.xianshen.mumirrorb.pojo.VO.RecordVO::getId).toList());
        query.setMood("不存在");
        assertTrue(service.list(query, user).isEmpty());
    }

    @Test void simultaneousFirstVisitsCreateOneCompleteSetOfGrowthAxes() throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(org.xianshen.mumirrorb.mapper.GrowthAxisMapper.class);
        config.addMapper(org.xianshen.mumirrorb.mapper.GrowthAxisHistoryMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        factory.setTypeHandlers(new org.xianshen.mumirrorb.common.handler.UuidTypeHandler());
        var sessions = new org.mybatis.spring.SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var realAxes = sessions.getMapper(org.xianshen.mumirrorb.mapper.GrowthAxisMapper.class);
        var axes = mock(org.xianshen.mumirrorb.mapper.GrowthAxisMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(realAxes));
        var bothRead = new java.util.concurrent.CountDownLatch(2);
        doAnswer(call -> {
            var result = realAxes.selectList(call.getArgument(0));
            bothRead.countDown();
            assertTrue(bothRead.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return result;
        }).when(axes).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var service = new org.xianshen.mumirrorb.service.impl.GrowthServiceImpl(axes, null,
                sessions.getMapper(org.xianshen.mumirrorb.mapper.GrowthAxisHistoryMapper.class), null,
                new org.xianshen.mumirrorb.config.GrowthProperties());
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> tx.execute(status -> service.axes(user)));
            var second = executor.submit(() -> tx.execute(status -> service.axes(user)));
            assertEquals(5, first.get(20, java.util.concurrent.TimeUnit.SECONDS).size());
            assertEquals(5, second.get(20, java.util.concurrent.TimeUnit.SECONDS).size());
            assertEquals(5, db.queryForObject("SELECT count(*) FROM growth_axes WHERE user_id=?", Long.class, user));
        } finally { executor.shutdownNow(); }
    }
}
