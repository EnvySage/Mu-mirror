package org.xianshen.mumirrorb.pipeline;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * classified_segment 状态机支撑逻辑测试（设计文档 13.1）
 *
 * 状态机规则（5.3）：
 * - AI 分类回填 metadata → classified_segment 写入当时文本
 * - 用户改文本 → classified_segment 置 NULL
 * - 改元数据 → classified_segment 不变
 * - 手动新增 Chunk → 初始 NULL
 * - confirm 补分类判据：classified_segment IS NULL
 */
class ClassifiedSegmentStateMachineTest {

    @Test
    void aiClassifiedItem_convertsMetadata_withTaskStatus() {
        RecordProcessorProto.ClassifyItem item = RecordProcessorProto.ClassifyItem.newBuilder()
                .setTitle("买牛奶")
                .setSummary("记得去超市")
                .setContentType(CommonProto.ContentType.TODO)
                .setStatus(CommonProto.TaskStatus.NOT_STARTED)
                .addMoods(CommonProto.MoodType.CALM)
                .addKeywords("超市")
                .build();

        Map<String, Object> metadata = ClassifyItemConverter.toMetadata(item);

        assertEquals("todo", metadata.get("contentType"));
        assertEquals("not_started", metadata.get("taskStatus")); // 裁决 #16：taskStatus 落 metadata
        assertEquals(java.util.List.of("calm"), metadata.get("mood"));
        assertEquals("买牛奶", metadata.get("title"));
    }

    @Test
    void unknownTaskStatus_omitted() {
        RecordProcessorProto.ClassifyItem item = RecordProcessorProto.ClassifyItem.newBuilder()
                .setTitle("感想")
                .setContentType(CommonProto.ContentType.THOUGHT)
                .setStatus(CommonProto.TaskStatus.STATUS_UNKNOWN)
                .build();

        Map<String, Object> metadata = ClassifyItemConverter.toMetadata(item);

        assertFalse(metadata.containsKey("taskStatus")); // 仅 todo/plan 有效
        assertEquals("thought", metadata.get("contentType"));
    }

    @Test
    void unknownContentType_null() {
        RecordProcessorProto.ClassifyItem item = RecordProcessorProto.ClassifyItem.newBuilder()
                .setTitle("t").build();
        Map<String, Object> metadata = ClassifyItemConverter.toMetadata(item);
        assertNull(metadata.get("contentType"));
    }

    /**
     * 模拟 ChunkService.update 的状态机逻辑：改文本 → NULL，改元数据 → 不变。
     * （服务层真实行为由 ConfirmReviewTest + 集成测试覆盖，此处固化规则语义）
     */
    @Test
    void updateRules_textChangeClearsClassifiedSegment() {
        Map<String, Object> state = new HashMap<>();
        state.put("classifiedSegment", "旧文本");

        boolean textChanged = true;   // 用户改了 segment 文本
        boolean metadataChanged = false;

        if (textChanged) {
            state.put("classifiedSegment", null);
        }
        Object userEdited = (textChanged || metadataChanged);

        assertNull(state.get("classifiedSegment")); // confirm 时将触发补分类
        assertTrue((Boolean) userEdited);
    }

    @Test
    void updateRules_metadataOnlyKeepsClassifiedSegment() {
        Map<String, Object> state = new HashMap<>();
        state.put("classifiedSegment", "旧文本");

        boolean textChanged = false;
        boolean metadataChanged = true; // 只改标签

        if (textChanged) {
            state.put("classifiedSegment", null);
        }

        assertEquals("旧文本", state.get("classifiedSegment")); // 只改标签：直接入库，不调 LLM
    }
}
