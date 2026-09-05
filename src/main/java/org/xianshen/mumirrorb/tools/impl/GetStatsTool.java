package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * get_stats 工具：五维统计复用（记录数/情绪分布/待办剩余）——"我上个月都在焦虑什么"类汇总型问题
 */
@Component
@RequiredArgsConstructor
public class GetStatsTool implements ToolExecutor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ProfileStatsMapper statsMapper;

    @Override
    public String name() {
        return "get_stats";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "获取用户一段时间内的统计数据：记录数、情绪分布、待办剩余。适合\"我最近状态如何/我上个月都在忙什么\"类汇总问题。",
                "{\"days\": 30}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        int days = SearchRecordsTool.intOf(args.get("days"), 30);
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(days).atStartOfDay(ZONE).toOffsetDateTime();

        long recordCount = statsMapper.countUserRecords(userId, since);
        Map<String, Long> moods = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.selectMoodStats(userId, since)) {
            moods.put(String.valueOf(row.get("mood")), (long) SearchRecordsTool.intOf(row.get("count"), 0));
        }
        int totalTodos = 0;
        int completed = 0;
        for (Map<String, Object> row : statsMapper.selectTodoStatusCounts(userId)) {
            int count = SearchRecordsTool.intOf(row.get("count"), 0);
            totalTodos += count;
            if ("completed".equals(String.valueOf(row.get("status")))) {
                completed = count;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("days", days);
        payload.put("record_count", recordCount);
        payload.put("moods", moods);
        payload.put("todos", Map.of("total", totalTodos, "completed", completed,
                "remaining", Math.max(totalTodos - completed, 0)));
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":记录" + recordCount + "条/" + days + "天")
                .payload(payload)
                .build();
    }
}
