package org.xianshen.mumirrorb.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.RecordActionHandler;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;
import org.xianshen.mumirrorb.service.*;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;
import org.xianshen.mumirrorb.service.impl.RecordDraftServiceImpl;
import org.xianshen.mumirrorb.tools.ToolCallContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real tool -> proposal service -> record handler -> draft service; only I/O is mocked. */
class PrepareRecordToolIntegrationTest {
    @Test
    void assistantRecordDefaultsToAiPreparationEvenWhenPageDefaultIsOnlySave() throws Exception {
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var json = new ObjectMapper();
        var mapper = mock(ActionProposalMapper.class);
        when(mapper.sessionOwned(user, session)).thenReturn(true);
        when(mapper.sourceMessageOwned(user, session, 902L)).thenReturn(true);
        var captured = new AtomicReference<ActionProposal>();
        when(mapper.insertIfAbsent(any())).thenAnswer(call -> { captured.set(call.getArgument(0)); return 1; });
        var ai = mock(AiGrpcClient.class);
        var settings = mock(SettingsService.class);
        when(settings.getSettings(user)).thenReturn(SettingsVO.builder().defaultRecordMode("ONLY_SAVE").build());
        var records = mock(RecordService.class);
        var handler = new RecordActionHandler(new RecordDraftServiceImpl(ai, settings), records,
                mock(RecordMapper.class), mock(ChunkMapper.class), json, settings);
        var proposals = new ActionProposalServiceImpl(mapper, List.of(handler));
        var tool = new PrepareRecordTool(proposals, json);

        tool.execute(user, Map.of("kind", "record.create", "rawContent", "完成了项目修复并补充回归测试"),
                new ToolCallContext(session, 902L, "assistant-ai-default"));

        assertEquals("AI_ASSISTED", captured.get().getPayload().path("mode").asText());
        assertEquals("RUNNING", captured.get().getPreparationStatus());
        assertEquals("PENDING", captured.get().getState());
        verifyNoInteractions(ai, records);
    }

    @Test
    void createsEditablePendingRecordWithRealPayloadValidationAndNoDomainWrite() throws Exception {
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var json = new ObjectMapper();
        var proposalMapper = mock(ActionProposalMapper.class);
        var recordMapper = mock(RecordMapper.class);
        var records = mock(RecordService.class);
        var ai = mock(AiGrpcClient.class);
        var settings = mock(SettingsService.class);
        when(settings.getSettings(user)).thenReturn(SettingsVO.builder().defaultRecordMode("ONLY_SAVE").build());
        when(proposalMapper.sessionOwned(user, session)).thenReturn(true);
        when(proposalMapper.sourceMessageOwned(user, session, 901L)).thenReturn(true);
        var captured = new AtomicReference<ActionProposal>();
        when(proposalMapper.insertIfAbsent(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return 1;
        });
        var drafts = new RecordDraftServiceImpl(ai, settings);
        var handler = new RecordActionHandler(drafts, records, recordMapper, mock(ChunkMapper.class), json, settings);
        var proposals = new ActionProposalServiceImpl(proposalMapper, List.of(handler));
        var tool = new PrepareRecordTool(proposals, json);

        var result = tool.execute(user, Map.of("kind", "record.create", "rawContent", "今天修 mirror 项目修了好久。", "mode", "ONLY_SAVE"),
                new ToolCallContext(session, 901L, "real-record-pipeline"));

        assertTrue(result.isSuccess());
        assertEquals("PENDING", captured.get().getState());
        assertEquals("READY", captured.get().getPreparationStatus());
        assertEquals("record.create", captured.get().getKind());
        assertFalse(captured.get().getPayload().has("kind"));
        assertEquals("今天修 mirror 项目修了好久。", captured.get().getPayload().path("recordDraft").path("rawContent").asText());
        assertFalse(captured.get().getPreview().path("businessWrites").asBoolean());
        verifyNoInteractions(ai, records, recordMapper);
    }
}
