package org.xianshen.mumirrorb.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatInvocationTest {

    @Test
    void assistantContextSourceIdsAreDeduplicatedAndDefensivelyCopied() {
        List<Long> sourceIds = new ArrayList<>(List.of(17L, 21L, 17L));
        ChatInvocation invocation = new ChatInvocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                101L, "请结合已确认记忆回答", "legacy", null, sourceIds);

        sourceIds.add(33L);

        assertEquals(List.of(17L, 21L), invocation.contextSourceMessageIds());
        assertThrows(UnsupportedOperationException.class,
                () -> invocation.contextSourceMessageIds().add(33L));
    }

    @Test
    void absentAssistantContextHasNoSourceProvenance() {
        ChatInvocation invocation = new ChatInvocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                101L, "普通问题", "legacy", null, null);

        assertEquals(List.of(), invocation.contextSourceMessageIds());
    }
}
