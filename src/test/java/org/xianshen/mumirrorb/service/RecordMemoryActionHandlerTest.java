package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.RecordMemoryActionHandler;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.service.impl.RecordMemoryLifecycleServiceImpl;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordMemoryActionHandlerTest {
    @Test
    void stopMemoryPreviewRequiresReadyAndExplainsReadonlyEffects() {
        UUID userId = UUID.randomUUID();
        RecordMapper records = mock(RecordMapper.class);
        Record ready = Record.builder().id(17L).userId(userId).memoryState(RecordMemoryState.READY)
                .processingVersion(6).build();
        when(records.selectOne(any())).thenReturn(ready);
        var handler = new RecordMemoryActionHandler(records, mock(RecordMemoryLifecycleService.class), new ObjectMapper());

        var preview = handler.validateAndPreview(userId, "record.memory.disable",
                new ObjectMapper().valueToTree(Map.of("recordId", 17L)));

        assertEquals("READY", preview.path("currentMemoryState").asText());
        assertEquals("DISABLED", preview.path("afterMemoryState").asText());
        assertTrue(preview.path("sourceReadonly").asBoolean());
        assertTrue(preview.path("keepsGrowthLedger").asBoolean());
        assertEquals("6", preview.path("targetVersions").path("17").asText());
    }

    @Test
    void stopMemoryCannotUnlockOrToggleNoneRecord() {
        UUID userId = UUID.randomUUID();
        RecordMapper records = mock(RecordMapper.class);
        when(records.selectOne(any())).thenReturn(Record.builder().id(17L).userId(userId)
                .memoryState(RecordMemoryState.NONE).processingVersion(0).build());
        var handler = new RecordMemoryActionHandler(records, mock(RecordMemoryLifecycleService.class), new ObjectMapper());

        assertThrows(RuntimeException.class, () -> handler.validateAndPreview(userId, "record.memory.disable",
                new ObjectMapper().valueToTree(Map.of("recordId", 17L))));
    }
}
