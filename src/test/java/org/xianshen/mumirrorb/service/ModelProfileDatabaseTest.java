package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.controller.ModelProfileController;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.UserModelProfile;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ModelProfileDTO;
import org.xianshen.mumirrorb.service.impl.SettingsServiceImpl;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL/MyBatis coverage in a fresh schema of an explicitly disposable DB. */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
class ModelProfileDatabaseTest {
    static JdbcTemplate db;
    static JdbcTemplate admin;
    static String schema;
    static SqlSessionTemplate sessions;
    static TransactionTemplate tx;
    static ModelProfileService service;
    static ModelProfileMapper profiles;
    static SettingsMapper settings;
    UUID user;

    @BeforeAll static void database() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),
                System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(ds);
        String name = admin.queryForObject("SELECT current_database()", String.class);
        if (name == null || !(name.equals("mirror_tarot_test") || name.startsWith("codex_assistant_growth_test_")))
            throw new IllegalStateException("Model profile tests require a named disposable test database");
        schema = "model_profiles_fixture_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var properties = new Properties(); properties.setProperty("currentSchema", schema); ds.setConnectionProperties(properties);
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        db.execute("CREATE TABLE user_settings(id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID UNIQUE NOT NULL REFERENCES users(id),"
                + "ai_provider VARCHAR(50), ai_protocol VARCHAR(20) DEFAULT 'anthropic',ai_api_key TEXT,ai_base_url TEXT,ai_model VARCHAR(100),"
                + "embedding_source VARCHAR(20) DEFAULT 'local',embedding_base_url TEXT,embedding_api_key TEXT,embedding_model VARCHAR(100),"
                + "review_mode VARCHAR(20) DEFAULT 'manual',default_record_mode VARCHAR(20) DEFAULT 'ONLY_SAVE',rag_half_life INT DEFAULT 30,"
                + "mirror_lookback INT DEFAULT 1,created_at TIMESTAMPTZ DEFAULT now(),updated_at TIMESTAMPTZ DEFAULT now())");
        String migration = new ClassPathResource("db/migration-v15-model-profiles.sql").getContentAsString(StandardCharsets.UTF_8);
        db.execute(migration); db.execute(migration); // Migration is repeatable.
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ModelProfileMapper.class); config.addMapper(SettingsMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setTypeHandlers(new UuidTypeHandler());
        sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        profiles = sessions.getMapper(ModelProfileMapper.class); settings = sessions.getMapper(SettingsMapper.class);
        service = new ModelProfileService(profiles, settings, mock(AiGrpcClient.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll static void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @BeforeEach void user() {
        user = UUID.randomUUID(); db.update("INSERT INTO users(id) VALUES (?)", user);
        db.update("INSERT INTO user_settings(user_id,ai_provider,ai_protocol,ai_base_url,ai_model,ai_api_key,embedding_model) VALUES(?,?,'openai',?,?,?,'untouched-embedding')",
                user, "legacy", "https://legacy.test/v1", "legacy-model", CryptoUtils.encrypt("legacy-key"));
    }

    ModelProfileDTO form(String name) {
        var dto = new ModelProfileDTO(); dto.setName(name); dto.setAiProvider("provider"); dto.setAiProtocol("anthropic");
        dto.setAiBaseUrl("https://new.test/v1"); dto.setAiModel("new-model"); dto.setAiApiKey("new-key"); return dto;
    }

    @Test void persistedLifecyclePreservesLegacyUntilApplyEnforcesOwnershipAndExposesNoSecret() throws Exception {
        var imported = tx.execute(s -> service.list(user)).get(0);
        assertTrue(imported.isActive()); assertFalse(imported.isUnappliedChanges());
        var created = tx.execute(s -> service.create(user, form("第二模型")));
        assertEquals("legacy-model", settings.selectForUpdate(user).getAiModel());
        assertEquals("new-key", CryptoUtils.decrypt(profiles.selectById(created.getId()).getAiApiKey()));
        UUID outsider = UUID.randomUUID(); db.update("INSERT INTO users(id) VALUES (?)", outsider);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> tx.execute(s -> service.activate(outsider, created.getId()))).getCode());
        assertThrows(BusinessException.class, () -> tx.execute(s -> { service.delete(outsider, created.getId()); return null; }));
        var applied = tx.execute(s -> service.activate(user, created.getId())); assertTrue(applied.isActive());
        UserSettings actual = settings.selectForUpdate(user);
        assertEquals("new-model", actual.getAiModel()); assertEquals("anthropic", actual.getAiProtocol());
        assertEquals(created.getId(), actual.getActiveModelProfileId());
        assertEquals("untouched-embedding", actual.getEmbeddingModel());
        assertEquals("new-key", CryptoUtils.decrypt(actual.getAiApiKey()));
        var consumer = new AiGrpcClient(mock(io.grpc.ManagedChannel.class), settings,
                mock(GlossaryService.class), mock(org.springframework.context.ApplicationContext.class),
                mock(ChunkMapper.class), new org.xianshen.mumirrorb.config.RecordContextProperties());
        var runtimeConfig = consumer.buildLlmConfigFor(user);
        assertEquals("new-model", runtimeConfig.getModel()); assertEquals("new-key", runtimeConfig.getApiKey());
        assertEquals(org.xianshen.mumirrorb.grpc.gen.CommonProto.AiProtocol.ANTHROPIC, runtimeConfig.getProtocol());
        var dto = form("改名与模型"); dto.setAiApiKey(""); dto.setAiModel("edited-model");
        assertTrue(tx.execute(s -> service.update(user, created.getId(), dto)).isUnappliedChanges());
        assertEquals("new-model", settings.selectForUpdate(user).getAiModel());
        tx.execute(s -> service.activate(user, created.getId()));
        assertEquals("edited-model", settings.selectForUpdate(user).getAiModel());
        assertThrows(BusinessException.class, () -> tx.execute(s -> { service.delete(user, created.getId()); return null; }));
        tx.execute(s -> { service.delete(user, imported.getId()); return null; });
        assertNull(profiles.selectById(imported.getId()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), "unused"));
        try {
            var http = MockMvcBuilders.standaloneSetup(new ModelProfileController(service)).build();
            http.perform(post("/settings/model-profiles/" + created.getId() + "/activate"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true))
                    .andExpect(jsonPath("$.data.aiApiKey").doesNotExist());
            assertEquals("edited-model", consumer.buildLlmConfigFor(user).getModel());
            http.perform(get("/settings/model-profiles")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].active").value(true))
                    .andExpect(jsonPath("$.data[0].aiApiKey").doesNotExist())
                    .andExpect(jsonPath("$.data[0].hasApiKey").value(true));
        } finally { SecurityContextHolder.clearContext(); }
        // A legacy settings write clears the marker under the same row lock.
        var legacy = new SettingsServiceImpl(settings, mock(AiGrpcClient.class));
        var changes = new org.xianshen.mumirrorb.pojo.DTO.SettingsDTO(); changes.setAiModel("direct-edit");
        tx.execute(s -> legacy.updateSettings(changes, user));
        assertNull(settings.selectForUpdate(user).getActiveModelProfileId());
        assertFalse(tx.execute(s -> service.list(user)).get(0).isActive());
    }

    @Test void simultaneousImportsAndActivationsHaveOneEffectiveProfile() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var calls = new ArrayList<Callable<UUID>>();
            for (int i = 0; i < 12; i++) calls.add(() -> tx.execute(s -> service.list(user)).get(0).getId());
            var results = pool.invokeAll(calls); UUID importedId = results.get(0).get();
            for (var result : results) assertEquals(importedId, result.get());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM user_model_profiles WHERE user_id=?", Integer.class, user));
            var second = tx.execute(s -> service.create(user, form("候选模型")));
            calls.clear();
            for (int i = 0; i < 12; i++) {
                UUID id = i % 2 == 0 ? importedId : second.getId();
                calls.add(() -> tx.execute(s -> service.activate(user, id)).getId());
            }
            for (var result : pool.invokeAll(calls)) result.get();
            var effective = settings.selectForUpdate(user); var active = profiles.selectById(effective.getActiveModelProfileId());
            assertEquals(active.getAiModel(), effective.getAiModel());
            assertEquals(active.getAiApiKey(), effective.getAiApiKey());
            assertEquals(1, tx.execute(s -> service.list(user)).stream().filter(v -> v.isActive()).count());
        } finally { pool.shutdownNow(); }
    }
}
