package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * search_records 工具（工具表第 1 行）：四路检索的 STRUCTURED 通道复用（SQL 元数据过滤，免 Embed）
 *
 * <p>PlanTools 场景优先走纯 SQL（快、不依赖 Embedding 可用性）；query 关键词过滤由
 * Structured SQL 已支持 contentType/moods/time_range——关键词检索在此版用 segment ILIKE 补充。</p>
 */
@Component
@RequiredArgsConstructor
public class SearchRecordsTool implements ToolExecutor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LIMIT = 20;

    private final ChatSearchMapper searchMapper;

    @Override
    public String name() {
        return "search_records";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "按条件检索用户的日记记录片段。适合\"我最近做了什么/上周学了什么\"类问题。不传 query 则按时间倒序取最近记录。",
                "{\"query\": \"关键词（可选）\", \"days\": 7, \"moods\": [\"happy\"], \"content_type\": \"learning|todo|...\", \"limit\": 10}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        int days = intOf(args.get("days"), 0);
        int limit = Math.min(intOf(args.get("limit"), 10), MAX_LIMIT);
        String contentType = strOf(args.get("content_type"));
        OffsetDateTime since = days > 0
                ? LocalDate.now(ZONE).minusDays(days).atStartOfDay(ZONE).toOffsetDateTime() : null;

        List<RetrievedChunkDTO> rows = searchMapper.searchStructured(
                userId, emptyToNull(contentType), null, null, since, null, limit);

        String query = strOf(args.get("query"));
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            rows = rows.stream().filter(r ->
                    (r.getContent() != null && r.getContent().contains(q))
                            || (r.getTitle() != null && r.getTitle().contains(q))).toList();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (RetrievedChunkDTO r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("record_id", r.getRecordId());
            m.put("title", r.getTitle());
            m.put("quote", preview(r.getContent(), 120));
            m.put("date", r.getCreatedAt());
            m.put("content_type", r.getContentType());
            items.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("records", items);
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + items.size() + "条")
                .payload(payload)
                .build();
    }

    static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase();
    }
}
