package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.xianshen.mumirrorb.common.security.AgentCommandAuthFilter;
import org.xianshen.mumirrorb.common.security.JwtAuthenticationFilter;
import org.xianshen.mumirrorb.common.exception.GlobalExceptionHandler;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.config.ChatEngineProperties;
import org.xianshen.mumirrorb.config.SecurityConfig;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentCommandAuthTest {

    private static final String KEY = "test-only-command-key-with-at-least-32-bytes";
    private final AgentProperties properties = new AgentProperties();
    private final ChatEngineProperties engineProperties = new ChatEngineProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AgentWriteGrantService grantService;
    private AgentCommandAuthFilter filter;
    private UUID userId;
    private UUID sessionId;
    private UUID runId;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        properties.setCommandHmacKey(KEY);
        engineProperties.setCreateRecordEnabled(true);
        grantService = new AgentWriteGrantService(properties, engineProperties, objectMapper);
        filter = new AgentCommandAuthFilter(grantService, objectMapper);
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        runId = UUID.randomUUID();
        run = AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId)
                .engine("graph_v2").mode("live").status("running")
                .startedAt(OffsetDateTime.now().minusSeconds(2))
                .leaseExpiresAt(OffsetDateTime.now().plusMinutes(1)).build();
    }

    @Test
    void onlyAnchoredExplicitPhrasesReceiveOneBoundedGrant() {
        for (String prefix : new String[]{"帮我记一篇日记：", "帮我创建一篇日记:", "帮我保存一条记录："}) {
            CreateGrant grant = grantService.authorizeCreate(prefix + "  原文 \r\n下一行  ", run).orElseThrow();
            assertEquals("原文 \n下一行", grant.authorizedContent());
            assertEquals(AgentWriteGrantService.sha256(grant.authorizedContent()), grant.requestHash());
            assertEquals(AgentWriteGrantService.callId(runId), grant.callId());
            assertFalse(grantService.verify(grant.token()).requestAiProcessing());
        }
        for (String question : new String[]{
                "", "帮我记一篇日记：   ", "请确认：帮我记一篇日记：正文",
                "历史里写着：帮我记一篇日记：正文", "文件内容：帮我保存一条记录：正文",
                "帮我保存记录：正文", "帮我记一篇日记：" + "字".repeat(2001)
        }) {
            assertTrue(grantService.authorizeCreate(question, run).isEmpty(), question);
        }
        assertTrue(grantService.authorizeCreate("帮我记一篇日记：" + "😀".repeat(1001), run).isEmpty(),
                "the 2000-character limit uses UTF-16 units");
    }

    @Test
    void grantBindsIdentityContentAndExpiresWithRunBudget() {
        String question = "帮我记一篇日记：原文";
        CreateGrant issued = grantService.authorizeCreate(question, run).orElseThrow();
        VerifiedGrant verified = grantService.verify(issued.token());

        assertEquals(userId, verified.userId());
        assertEquals(sessionId, verified.sessionId());
        assertEquals(runId, verified.runId());
        assertEquals(AgentWriteGrantService.sha256("原文"), verified.contentHash());
        assertTrue(verified.expiresAt().isBefore(run.getStartedAt().plusMinutes(9)));
        assertTrue(grantService.authorizeCreate("帮我记一篇日记：原文", copyRun("shadow", "running", run.getLeaseExpiresAt())).isEmpty());
        assertTrue(grantService.authorizeCreate("帮我记一篇日记：原文", copyRun("live", "cancel_requested", run.getLeaseExpiresAt())).isEmpty());
        assertTrue(grantService.authorizeCreate("帮我记一篇日记：原文", copyRun("live", "running", OffsetDateTime.now().minusSeconds(1))).isEmpty());
    }

    @Test
    void createRecordFeatureSwitchDefaultsOff() {
        assertFalse(new ChatEngineProperties().isCreateRecordEnabled());
    }

    @Test
    void signedGrantRejectsTamperingAndMissingKey() {
        String token = grantService.authorizeCreate("帮我记一篇日记：原文", run).orElseThrow().token();
        String tampered = (token.charAt(0) == 'a' ? "b" : "a") + token.substring(1);
        assertThrows(AgentCommandException.class, () -> grantService.verify(tampered));

        properties.setCommandHmacKey("");
        assertTrue(grantService.authorizeCreate("帮我记一篇日记：原文", run).isEmpty());
        assertThrows(AgentCommandException.class, () -> grantService.verify(token));
    }

    @Test
    void internalPathRequiresCommandGrantAndRejectsUserJwt() throws Exception {
        MockHttpServletRequest missing = request();
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        FilterChain chain = (req, res) -> called.set(true);
        filter.doFilter(missing, missingResponse, chain);
        assertEquals(401, missingResponse.getStatus());
        assertFalse(called.get());

        MockHttpServletRequest jwt = request();
        jwt.addHeader("Authorization", "Bearer user-jwt");
        MockHttpServletResponse jwtResponse = new MockHttpServletResponse();
        filter.doFilter(jwt, jwtResponse, chain);
        assertEquals(401, jwtResponse.getStatus());
        assertFalse(called.get());

        String token = grantService.authorizeCreate("帮我记一篇日记：原文", run).orElseThrow().token();
        MockHttpServletRequest valid = request();
        valid.addHeader(AgentCommandAuthFilter.GRANT_HEADER, token);
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        AtomicBoolean trustedPrincipalInstalled = new AtomicBoolean();
        filter.doFilter(valid, validResponse, (req, res) -> trustedPrincipalInstalled.set(
                SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof VerifiedGrant));
        assertTrue(trustedPrincipalInstalled.get());
        assertEquals(200, validResponse.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void strictJsonRejectsModelSuppliedTitleAndDateAndServletDoesNotRegisterFilterTwice() {
        assertThrows(Exception.class, () -> objectMapper.readValue(
                "{\"run_id\":\"" + runId + "\",\"call_id\":\"" + UUID.randomUUID()
                        + "\",\"content\":\"正文\",\"title\":\"标题\",\"date\":\"2026-10-20\"}",
                AgentCreateRecordRequest.class));
        var registration = new SecurityConfig(mock(JwtAuthenticationFilter.class), filter)
                .agentCommandAuthFilterRegistration();
        assertFalse(registration.isEnabled());
    }

    @Test
    void malformedInternalCommandBodyHasStablePlain422Response() {
        MockHttpServletRequest request = request();
        var response = new GlobalExceptionHandler().handleUnreadableRequestBody(
                new HttpMessageNotReadableException("invalid command body"), request);
        assertEquals(422, response.getStatusCode().value());
        assertEquals(java.util.Map.of("code", "INVALID_COMMAND_REQUEST"), response.getBody());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/api");
        request.setRequestURI("/api/internal/agent/commands/create-record");
        return request;
    }

    private AgentRun copyRun(String mode, String status, OffsetDateTime leaseExpiresAt) {
        return AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId).engine("graph_v2")
                .mode(mode).status(status).startedAt(run.getStartedAt()).leaseExpiresAt(leaseExpiresAt).build();
    }
}
