package org.xianshen.mumirrorb.pipeline;

import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClassifyItem → chunks.metadata 转换器（管道 / 审核链路共用）
 *
 * <p>三处调用共享同一转换逻辑，保证 metadata 结构一致：</p>
 * <ul>
 *   <li>{@link ClassifyProcessor}：管道拆分分类</li>
 *   <li>ChunkService#add：手动新增片段的 single 分类回填</li>
 *   <li>RecordService#confirmReview：confirm 补分类回填（5.4）</li>
 * </ul>
 *
 * <p>metadata 结构（设计文档 3.2）：title / summary / contentType / mood / keywords / taskStatus。</p>
 */
public final class ClassifyItemConverter {

    private ClassifyItemConverter() {
    }

    /**
     * ClassifyItem → metadata Map
     *
     * <p>taskStatus 仅 todo/plan 类有效（裁决 #16：写入 metadata.taskStatus，待办聚合依赖它），
     * UNKNOWN 时省略该键。</p>
     */
    public static Map<String, Object> toMetadata(RecordProcessorProto.ClassifyItem item) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", item.getTitle());
        metadata.put("summary", item.getSummary());
        metadata.put("contentType", convertContentType(item.getContentType()));
        metadata.put("mood", convertMoods(item.getMoodsList()));
        metadata.put("keywords", item.getKeywordsList());
        String taskStatus = convertTaskStatus(item.getStatus());
        if (taskStatus != null) {
            metadata.put("taskStatus", taskStatus);
        }
        return metadata;
    }

    /**
     * Proto ContentType → 英文小写字符串（UNKNOWN → null）
     */
    public static String convertContentType(CommonProto.ContentType protoType) {
        if (protoType == CommonProto.ContentType.CONTENT_UNKNOWN) {
            return null;
        }
        return protoType.name().toLowerCase();
    }

    /**
     * Proto MoodType 列表 → 英文小写字符串列表
     */
    public static List<String> convertMoods(List<CommonProto.MoodType> moodsList) {
        return moodsList.stream()
                .filter(m -> m != CommonProto.MoodType.MOOD_UNKNOWN)
                .map(Enum::name)
                .map(String::toLowerCase)
                .toList();
    }

    /**
     * Proto TaskStatus → 英文小写字符串（UNKNOWN → null）
     */
    public static String convertTaskStatus(CommonProto.TaskStatus taskStatus) {
        if (taskStatus == CommonProto.TaskStatus.STATUS_UNKNOWN) {
            return null;
        }
        return taskStatus.name().toLowerCase();
    }
}
