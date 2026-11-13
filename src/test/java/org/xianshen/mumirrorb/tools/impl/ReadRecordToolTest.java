package org.xianshen.mumirrorb.tools.impl;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.tools.ToolExecutor;
import org.xianshen.mumirrorb.tools.ToolRegistry;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadRecordToolTest {
    @Test void fullTextReaderIsRegisteredAsReadTool() throws Exception {
        var type=assertDoesNotThrow(()->Class.forName("org.xianshen.mumirrorb.tools.impl.ReadRecordTool"));
        var reader=(ToolExecutor)type.getConstructor(ChatSearchMapper.class).newInstance(mock(ChatSearchMapper.class));
        var registry=new ToolRegistry(List.of(reader));
        var spec=registry.toProtoSpecs().get(0);
        assertEquals("read_record",spec.getName());
        assertTrue(spec.getArgsSchema().contains("record_id"));
        assertTrue(spec.getArgsSchema().contains("offset"));
        assertEquals("READ",spec.getEffect());
    }
}
