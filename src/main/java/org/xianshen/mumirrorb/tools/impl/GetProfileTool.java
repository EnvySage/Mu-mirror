package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * get_profile 工具：最新快照五维 + 上次快照时间——"我是什么样的人"类问题
 */
@Component
@RequiredArgsConstructor
public class GetProfileTool implements ToolExecutor {

    private final ProfileSnapshotMapper snapshotMapper;

    @Override
    public String name() {
        return "get_profile";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "获取镜子画像最新快照：情绪/学习/待办/节奏分析与整体总结。适合\"我是什么样的人/分析一下我\"类问题。",
                "{}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        ProfileSnapshot snapshot = snapshotMapper.selectLatest(userId, "manual");
        if (snapshot == null) {
            snapshot = snapshotMapper.selectLatest(userId, "monthly");
        }
        if (snapshot == null) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + ":尚无画像")
                    .payload(Map.of("available", false,
                            "message", "用户还没有生成过画像快照"))
                    .build();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", true);
        payload.put("snapshot_type", snapshot.getSnapshotType());
        payload.put("created_at", snapshot.getCreatedAt() == null ? null
                : snapshot.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        payload.put("overall_summary", snapshot.getOverallSummary());
        payload.put("mood_analysis", snapshot.getMoodAnalysis());
        payload.put("learning_analysis", snapshot.getLearningAnalysis());
        payload.put("todo_analysis", snapshot.getTodoAnalysis());
        payload.put("rhythm_analysis", snapshot.getRhythmAnalysis());
        payload.put("user_tags", snapshot.getUserTags());
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + snapshot.getSnapshotType() + "快照")
                .payload(payload)
                .build();
    }
}
