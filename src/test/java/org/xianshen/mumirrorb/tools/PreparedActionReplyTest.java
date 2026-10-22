package org.xianshen.mumirrorb.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedActionReplyTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"PENDING,确认后", "CANCELLED,已取消", "SUCCEEDED,此前已执行完成", "EXECUTING,正在执行", "STALE,目标已发生变化", "FAILED,执行失败"})
    void usesPersistedCardState(String state, String expected) throws Exception {
        var payload = json.readTree("{\"proposal\":{\"id\":\"card-1\",\"state\":\"" + state + "\"}}");
        var reply = PreparedActionReply.from("prepare_record", true, payload);
        assertTrue(reply.text().contains(expected));
        if (!"PENDING".equals(state)) assertFalse(reply.text().contains("确认后"));
    }

    @Test
    void malformedSuccessCannotClaimACardWasPrepared() throws Exception {
        for (String payload : new String[]{"{}", "{\"proposal\":{}}", "{\"proposal\":{\"id\":\"x\",\"state\":\"UNKNOWN\"}}"}) {
            assertEquals("action_failed", PreparedActionReply.from("prepare_record", true, json.readTree(payload)).outcome());
        }
    }

    @Test
    void targetSelectionIsAQuestionNotACreatedCard() throws Exception {
        var reply = PreparedActionReply.from("prepare_todo", true, json.readTree(
                "{\"requiresTargetSelection\":true,\"todos\":[{\"id\":7,\"title\":\"修复工具调用\"}]}"));
        assertEquals("clarification", reply.outcome());
        assertTrue(reply.text().contains("#7 修复工具调用"));
    }
}
