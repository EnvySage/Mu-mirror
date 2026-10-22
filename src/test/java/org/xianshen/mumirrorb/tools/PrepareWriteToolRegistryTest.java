package org.xianshen.mumirrorb.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import org.xianshen.mumirrorb.tools.impl.PrepareGrowthAdjustmentTool;
import org.xianshen.mumirrorb.tools.impl.PrepareMemoryChangeTool;
import org.xianshen.mumirrorb.tools.impl.PrepareRecordTool;
import org.xianshen.mumirrorb.tools.impl.PrepareTodoTool;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PrepareWriteToolRegistryTest {

    @Test
    void exposesEveryExistingConfirmationCardToolWithPrepareWriteEffect() {
        ObjectMapper json = new ObjectMapper();
        ActionProposalService actions = mock(ActionProposalService.class);
        ToolRegistry registry = new ToolRegistry(List.of(
                new PrepareRecordTool(actions, json),
                new PrepareTodoTool(actions, mock(TodoRegistryService.class), json),
                new PrepareMemoryChangeTool(mock(ChatMemoryService.class)),
                new PrepareGrowthAdjustmentTool(actions, json)
        ));

        var definitions = registry.prepareWriteProtoSpecs();
        Set<String> names = definitions.stream().map(org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolSpec::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("prepare_record", "prepare_todo", "prepare_memory_change",
                "prepare_growth_adjustment"), names);
        assertTrue(definitions.stream().allMatch(spec -> "PREPARE_WRITE".equals(spec.getEffect())));
    }
}
