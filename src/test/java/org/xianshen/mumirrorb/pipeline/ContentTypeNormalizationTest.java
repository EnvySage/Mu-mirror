package org.xianshen.mumirrorb.pipeline;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import static org.junit.jupiter.api.Assertions.*;

class ContentTypeNormalizationTest {
    @Test
    void legacyPlansUseTheCanonicalTodoCategory() {
        assertEquals("todo", ClassifyItemConverter.convertContentType(CommonProto.ContentType.PLAN));
        assertEquals("todo", ClassifyItemConverter.convertContentType(CommonProto.ContentType.TODO));
        assertEquals("note", ClassifyItemConverter.convertContentType(CommonProto.ContentType.NOTE));
    }
}
