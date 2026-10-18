package org.xianshen.mumirrorb.tools.impl;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import org.xianshen.mumirrorb.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrepareTodoToolTest {
    @Test
    void ambiguousTargetReturnsChoicesWithoutPreparingOrWriting() {
        var actions=mock(ActionProposalService.class); var todos=mock(TodoRegistryService.class);
        UUID owner=UUID.randomUUID(); when(todos.listAll(owner)).thenReturn(List.of());
        var tool=new PrepareTodoTool(actions,todos,new ObjectMapper());
        var result=tool.execute(owner,Map.of("kind","todo.complete"),new ToolCallContext(UUID.randomUUID(),71L,"trusted"));
        assertTrue(result.isSuccess());
        assertEquals(true,((Map<?,?>)result.getPayload()).get("requiresTargetSelection"));
        verifyNoInteractions(actions); verify(todos).listAll(owner);
    }
    @Test
    void refusesForgedAuthorization() {
        var actions = mock(ActionProposalService.class);
        var todos = mock(TodoRegistryService.class);
        var tool = new PrepareTodoTool(actions, todos, new ObjectMapper());
        assertEquals(ToolEffect.PREPARE_WRITE, tool.effect());
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(UUID.randomUUID(), Map.of("userId", UUID.randomUUID().toString(), "confirmed", true)));
        verifyNoInteractions(actions, todos);
    }
}
