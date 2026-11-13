package org.xianshen.mumirrorb.tools.impl;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacySearchPaginationTest {
    @Test void searchAdvertisesExcerptsAndPaging() {
        var tool=new SearchRecordsTool(mock(ChatSearchMapper.class),mock(AiGrpcClient.class),mock(SettingsMapper.class),new MirrorProperties());
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)tool.execute(UUID.randomUUID(),Map.of("mode","structured")).getPayload();
        assertEquals("excerpt",payload.get("content_scope"));
        assertEquals(0,payload.get("offset"));
        assertEquals(false,payload.get("truncated"));
        assertTrue(payload.containsKey("next_offset"));
    }

    @Test void longRecordRemainsAnExplicit120CharacterExcerpt() {
        var mapper=mock(ChatSearchMapper.class);
        String source="authorized text ".repeat(30)+"TAIL_FACT";
        when(mapper.searchRecordsStructured(any(),any(),any(),any(),any(),any(),any(),anyInt(),org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(List.of(RetrievedChunkDTO.builder().chunkId(5L).recordId(9L).content(source).build()));
        var tool=new SearchRecordsTool(mapper,mock(AiGrpcClient.class),mock(SettingsMapper.class),new MirrorProperties());

        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)tool.execute(UUID.randomUUID(),Map.of("mode","structured")).getPayload();
        @SuppressWarnings("unchecked") var records=(List<Map<String,Object>>)payload.get("records");

        assertEquals("excerpt",payload.get("content_scope"));
        String quote=(String)records.get(0).get("quote");
        assertEquals(121,quote.length()); // 120 chars plus the visible truncation marker
        assertTrue(quote.endsWith("…"));
        assertFalse(quote.contains("TAIL_FACT"));
    }
}
