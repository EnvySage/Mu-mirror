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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * compare_snapshots 工具：两份快照 Δ 摘要（缺省=最近两份）——"我和上个月比有什么变化"
 */
@Component
@RequiredArgsConstructor
public class CompareSnapshotsTool implements ToolExecutor {

    private final ProfileSnapshotMapper snapshotMapper;

    @Override
    public String name() {
        return "compare_snapshots";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "对比两份镜子画像快照的变化（缺省=最近两份）。适合\"我和上个月比怎么样/我有什么变化\"。",
                "{\"a_id\": 12, \"b_id\": 13}  // 可选，缺省取最近两份");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        Long aId = SearchRecordsTool.intOf(args.get("a_id"), -1) > 0
                ? (long) SearchRecordsTool.intOf(args.get("a_id"), -1) : null;
        Long bId = SearchRecordsTool.intOf(args.get("b_id"), -1) > 0
                ? (long) SearchRecordsTool.intOf(args.get("b_id"), -1) : null;

        ProfileSnapshot a = aId != null ? owned(userId, aId) : null;
        ProfileSnapshot b = bId != null ? owned(userId, bId) : null;
        if (a == null || b == null) {
            // 缺省：最近两份（跨类型合并倒序）
            List<ProfileSnapshot> recent = snapshotMapper.selectAllByUser(userId);
            if (recent.size() < 2) {
                return ToolExecutionResult.builder()
                        .success(true)
                        .summary(name() + ":快照不足")
                        .payload(Map.of("available", false,
                                "message", "至少需要两份画像快照才能对比"))
                        .build();
            }
            a = recent.get(0);
            b = recent.get(1);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", true);
        payload.put("newer", describe(a));
        payload.put("older", describe(b));
        payload.put("diffs", diff(a, b));
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + label(a) + " vs " + label(b))
                .payload(payload)
                .build();
    }

    private ProfileSnapshot owned(UUID userId, Long id) {
        ProfileSnapshot s = snapshotMapper.selectById(id);
        return s != null && s.getUserId().equals(userId) ? s : null;
    }

    private Map<String, Object> describe(ProfileSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("type", s.getSnapshotType());
        m.put("created_at", s.getCreatedAt() == null ? null
                : s.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return m;
    }

    /**
     * 五维 Δ（新旧各一段截断对照，LLM 在 prompt 里做语义对比）
     */
    private static List<Map<String, String>> diff(ProfileSnapshot newer, ProfileSnapshot older) {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        add(out, "overall", newer.getOverallSummary(), older.getOverallSummary());
        add(out, "mood", newer.getMoodAnalysis(), older.getMoodAnalysis());
        add(out, "learning", newer.getLearningAnalysis(), older.getLearningAnalysis());
        add(out, "todo", newer.getTodoAnalysis(), older.getTodoAnalysis());
        add(out, "rhythm", newer.getRhythmAnalysis(), older.getRhythmAnalysis());
        return out;
    }

    private static void add(List<Map<String, String>> out, String dim, String n, String o) {
        if (n == null && o == null) {
            return;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("dimension", dim);
        m.put("newer", preview(n));
        m.put("older", preview(o));
        out.add(m);
    }

    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String label(ProfileSnapshot s) {
        return s.getCreatedAt() == null ? s.getSnapshotType()
                : s.getCreatedAt().format(DateTimeFormatter.ofPattern("M月"));
    }
}
