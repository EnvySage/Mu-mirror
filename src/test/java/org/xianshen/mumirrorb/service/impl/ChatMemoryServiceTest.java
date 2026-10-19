package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.MemoryActionHandler;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatMemoryServiceTest {
    @Test void endedDayRegistrationReturnsWhileModelRunsInBackground() throws Exception {
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID();
        LocalDate day = LocalDate.now().minusDays(1);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user).dayKey(day).build();
        ConversationHistory message = ConversationHistory.builder().id(91L).userId(user).sessionId(sessionId)
                .role("user").content("昨天去跑步了").createdAt(OffsetDateTime.now()).build();
        ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        AiGrpcClient ai = mock(AiGrpcClient.class);
        when(sessions.selectList(any())).thenReturn(List.of(session));
        when(history.selectUserMessages(sessionId, user)).thenReturn(List.of(message));
        java.util.concurrent.atomic.AtomicReference<String> leaseHash = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger findCount = new java.util.concurrent.atomic.AtomicInteger();
        when(summaries.find(user, day)).thenAnswer(invocation -> findCount.getAndIncrement() == 0
                ? null : processingLease(leaseHash.get(), 1L));
        when(summaries.insertLease(eq(user), eq(day), eq(sessionId), anyString())).thenAnswer(invocation -> {
            leaseHash.set(invocation.getArgument(3)); return 1;
        });
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(ai.summarizeDay(eq(user), eq(day), anyString(), anyList(), eq("Asia/Shanghai"))).thenAnswer(invocation -> {
            modelStarted.countDown();
            assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            throw new RuntimeException("model failed after release");
        });
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history,
                mock(ChatMemoryMapper.class), ai, mock(ActionProposalService.class), new ObjectMapper());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> registration = caller.submit(() -> service.prepareEndedDays(user, LocalDate.now()));
        try {
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
            registration.get(1, TimeUnit.SECONDS);
            verify(summaries).insertLease(eq(user), eq(day), eq(sessionId), anyString());
        } finally {
            releaseModel.countDown();
            caller.shutdownNow();
        }
    }

    @Test void retryAcquiresProcessingLeaseBeforeReturning() throws Exception {
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID();
        LocalDate day = LocalDate.now().minusDays(1);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user).dayKey(day).build();
        ConversationHistory message = ConversationHistory.builder().id(92L).userId(user).sessionId(sessionId)
                .role("user").content("昨天读完一本书").createdAt(OffsetDateTime.now()).build();
        ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        AiGrpcClient ai = mock(AiGrpcClient.class);
        when(sessions.selectById(sessionId)).thenReturn(session);
        CountDownLatch historyStarted = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        when(history.selectUserMessages(sessionId, user)).thenAnswer(invocation -> {
            historyStarted.countDown();
            assertTrue(releaseHistory.await(5, TimeUnit.SECONDS));
            return List.of(message);
        });
        java.util.concurrent.atomic.AtomicReference<String> leaseHash = new java.util.concurrent.atomic.AtomicReference<>(testSourceHash(message));
        var initialRow = failedLease(leaseHash.get(), 2L);
        initialRow.setSourceDate(day); initialRow.setSourceSessionId(sessionId);
        java.util.concurrent.atomic.AtomicReference<org.xianshen.mumirrorb.pojo.DO.ChatDaySummary> currentRow =
                new java.util.concurrent.atomic.AtomicReference<>(initialRow);
        when(summaries.find(user, day)).thenAnswer(invocation -> currentRow.get());
        when(summaries.listForUser(user)).thenAnswer(invocation -> List.of(currentRow.get()));
        when(summaries.acquireRetry(eq(user), eq(day), anyString())).thenAnswer(invocation -> {
            leaseHash.set(invocation.getArgument(2));
            var next = processingLease(leaseHash.get(), currentRow.get().getVersion() + 1);
            next.setSourceDate(day); next.setSourceSessionId(sessionId);
            currentRow.set(next); return 1;
        });
        CountDownLatch firstFailureStored = new CountDownLatch(1);
        when(summaries.fail(eq(user), eq(day), anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            currentRow.get().setState("FAILED");
            firstFailureStored.countDown(); return 1;
        });
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch secondModelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CountDownLatch releaseSecondModel = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.summarizeDay(eq(user), eq(day), anyString(), anyList(), eq("Asia/Shanghai"))).thenAnswer(invocation -> {
            if (modelCalls.getAndIncrement() == 0) {
                modelStarted.countDown();
                assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            } else {
                secondModelStarted.countDown();
                assertTrue(releaseSecondModel.await(5, TimeUnit.SECONDS));
            }
            throw new RuntimeException("model failed after release");
        });
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history,
                mock(ChatMemoryMapper.class), ai, mock(ActionProposalService.class), new ObjectMapper());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = caller.submit(() -> service.retry(user, sessionId, LocalDate.now()));
            assertTrue(historyStarted.await(2, TimeUnit.SECONDS));
            assertFalse(request.isDone(), "retry should not return before its processing lease is acquired");
            releaseHistory.countDown();
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
            request.get(1, TimeUnit.SECONDS);
            verify(summaries).acquireRetry(eq(user), eq(day), anyString());
            releaseModel.countDown();
            assertTrue(firstFailureStored.await(2, TimeUnit.SECONDS));

            service.retry(user, sessionId, LocalDate.now());
            assertTrue(secondModelStarted.await(2, TimeUnit.SECONDS));
            verify(summaries, times(2)).acquireRetry(eq(user), eq(day), anyString());
            assertEquals("PROCESSING", service.endedSummaries(user, LocalDate.now()).get(0).getState());
        } finally {
            releaseHistory.countDown();
            releaseModel.countDown();
            releaseSecondModel.countDown();
            caller.shutdownNow();
        }
    }

    @Test void noEmptyDaySummary() {
        ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        when(history.selectUserMessages(any(), any())).thenReturn(List.of());
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history,
                mock(ChatMemoryMapper.class), mock(AiGrpcClient.class), mock(ActionProposalService.class), new ObjectMapper());
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .dayKey(LocalDate.of(2026, 10, 18)).build();
        service.process(session.getUserId(), session);
        verifyNoInteractions(summaries);
        verifyNoInteractions(serviceAi(service));
    }

    @Test void candidatesNeverActiveBeforeConfirm() {
        ObjectMapper json = new ObjectMapper();
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        UUID sourceSession = UUID.randomUUID();
        when(chatMemory.validateDraft(any(), any())).thenReturn(sourceSession);
        MemoryActionHandler handler = new MemoryActionHandler(mock(ChatMemoryMapper.class), chatMemory, json);
        ObjectNode draft = json.createObjectNode().put("memoryId", UUID.randomUUID().toString())
                .put("content", "偏好早晨阅读").put("kind", "preference").put("sourceDate", "2026-10-18").put("version", 1);
        draft.putArray("sourceMessageIds").add(12L);
        ObjectNode payload = json.createObjectNode().set("memoryDraft", draft);
        var preview = handler.validateAndPreview(UUID.randomUUID(), "memory.accept", payload);
        assertFalse(preview.path("activeBeforeConfirm").asBoolean());
        assertTrue(preview.path("requiresConfirmation").asBoolean());
        verifyNoInteractions(handlerMapper(handler));
    }

    @Test void planCandidateHasNoDefaultExpiry() {
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 18);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user).dayKey(day).build();
        ConversationHistory source = ConversationHistory.builder().id(71L).userId(user).sessionId(sessionId)
                .role("user").content("下周要准备面试计划").createdAt(OffsetDateTime.now()).build();
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        ActionProposalService actions = mock(ActionProposalService.class);
        when(sessions.selectById(sessionId)).thenReturn(session);
        when(sessions.selectOne(any())).thenReturn(session);
        when(history.selectUserMessages(sessionId, user)).thenReturn(List.of(source));
        when(memories.countOwnedUserSources(eq(user), eq(sessionId), anyList())).thenReturn(1);
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(mock(ChatDaySummaryMapper.class), sessions, history,
                memories, mock(AiGrpcClient.class), actions, new ObjectMapper());

        service.prepareCandidate(user, sessionId, 71L, "plan-no-expiry",
                Map.of("content", "下周准备面试", "kind", "plan"));

        var argument = org.mockito.ArgumentCaptor.forClass(ActionPrepareDTO.class);
        verify(actions).prepare(eq(user), argument.capture());
        assertEquals("memory.accept", argument.getValue().getKind());
        assertFalse(argument.getValue().getPayload().path("memoryDraft").has("expiresAt"));
    }

    @Test void lateSummaryCannotPrepareCandidateAfterItsSourceWasForgotten() throws Exception {
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 18);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user).dayKey(day).build();
        ConversationHistory source = ConversationHistory.builder().id(73L).userId(user).sessionId(sessionId)
                .role("user").content("我已经忘掉的私人消息").createdAt(OffsetDateTime.now()).build();
        ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        AiGrpcClient ai = mock(AiGrpcClient.class);
        ActionProposalService actions = mock(ActionProposalService.class);
        when(history.selectUserMessages(sessionId, user)).thenReturn(List.of(source), List.of());
        when(summaries.insertLease(eq(user), eq(day), eq(sessionId), anyString())).thenReturn(1);
        java.util.concurrent.atomic.AtomicInteger leaseReads = new java.util.concurrent.atomic.AtomicInteger();
        when(summaries.find(user, day)).thenAnswer(invocation -> leaseReads.getAndIncrement() == 0
                ? null : processingLease(testSourceHash(source), 1L));
        when(ai.summarizeDay(eq(user), eq(day), anyString(), anyList(), eq("Asia/Shanghai"))).thenReturn(
                org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.SummarizeDayResponse.newBuilder()
                        .setSummary("summary").setSourceHash(testSourceHash(source))
                        .addMemoryCandidates(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.MemoryCandidate.newBuilder()
                                .setContent("private plan").setKind("plan").addSourceMessageIds(73L))
                        .build());
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history, memories, ai, actions, new ObjectMapper());

        service.process(user, session);

        verify(history, times(2)).selectUserMessages(sessionId, user);
        verify(actions, never()).prepare(eq(user), any(ActionPrepareDTO.class));
    }

    @Test void noModelConfigLeavesSummaryFailedAndRetryable() {
        ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        ChatMemoryMapper memoryMapper = mock(ChatMemoryMapper.class);
        AiGrpcClient ai = mock(AiGrpcClient.class);
        ActionProposalService actions = mock(ActionProposalService.class);
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 18);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user).dayKey(day).build();
        ConversationHistory message = ConversationHistory.builder().id(51L).userId(user).sessionId(sessionId)
                .role("user").content("我周末准备读书").createdAt(OffsetDateTime.now()).build();
        when(history.selectUserMessages(sessionId, user)).thenReturn(List.of(message));
        java.util.concurrent.atomic.AtomicReference<String> leaseHash = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger finds = new java.util.concurrent.atomic.AtomicInteger();
        when(summaries.find(user, day)).thenAnswer(invocation -> {
            if (finds.getAndIncrement() == 0) return null;
            var lease = new org.xianshen.mumirrorb.pojo.DO.ChatDaySummary();
            lease.setSourceHash(leaseHash.get()); lease.setState("PROCESSING"); lease.setVersion(1L);
            return lease;
        });
        when(summaries.insertLease(eq(user), eq(day), eq(sessionId), anyString())).thenAnswer(invocation -> {
            leaseHash.set(invocation.getArgument(3)); return 1;
        });
        when(summaries.fail(eq(user), eq(day), anyString(), eq(1L), anyString())).thenReturn(1);
        when(ai.summarizeDay(eq(user), eq(day), anyString(), anyList(), eq("Asia/Shanghai")))
                .thenThrow(new org.xianshen.mumirrorb.common.exception.BusinessException(400, "model not configured"));
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history, memoryMapper, ai, actions, new ObjectMapper());

        service.process(user, session);

        verify(summaries).fail(eq(user), eq(day), anyString(), eq(1L), eq("AI_NOT_CONFIGURED_OR_REJECTED"));
        verify(summaries, never()).complete(any(), any(), anyString(), anyLong(), anyString());
        verifyNoInteractions(actions, memoryMapper);
    }

    private static org.xianshen.mumirrorb.pojo.DO.ChatDaySummary processingLease(String hash, long version) {
        var lease = new org.xianshen.mumirrorb.pojo.DO.ChatDaySummary();
        lease.setSourceHash(hash); lease.setState("PROCESSING"); lease.setVersion(version);
        return lease;
    }
    private static org.xianshen.mumirrorb.pojo.DO.ChatDaySummary failedLease(String hash, long version) {
        var lease = processingLease(hash, version); lease.setState("FAILED"); return lease;
    }
    private static String testSourceHash(ConversationHistory message) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String value = message.getId() + "\u0000" + message.getCreatedAt() + "\u0000" + message.getContent() + "\n";
        return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static AiGrpcClient serviceAi(ChatMemoryServiceImpl service) {
        return (AiGrpcClient) org.springframework.test.util.ReflectionTestUtils.getField(service, "ai");
    }
    private static ChatMemoryMapper handlerMapper(MemoryActionHandler handler) {
        return (ChatMemoryMapper) org.springframework.test.util.ReflectionTestUtils.getField(handler, "memories");
    }
}
