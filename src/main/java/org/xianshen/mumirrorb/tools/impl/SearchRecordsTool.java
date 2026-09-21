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
                "按条件检索用户的日记记录片段。适合\"我最近做了什么/上周学了什么/某一天做了什么\"类问题。"
                        + "问某一天或某段日期时用 date 或 date_from/date_to（按记录时间精确过滤，把那几天的记录全部取回，"
                        + "此时不要再加 query）。query 是逐字匹配，只写原文里大概率出现的短词（如\"琴\"\"吉他\"），"
                        + "不要写概括性的词组（如\"弹曲子\"）。不传 query 则按时间倒序取记录。",
                "{\"date\": \"2026-09-12（查某一天，可选）\", \"date_from\": \"2026-09-01（可选）\", "
                        + "\"date_to\": \"2026-09-07（可选，含当天）\", \"query\": \"逐字匹配的短词（可选）\", "
                        + "\"days\": 7, \"moods\": [\"happy\"], \"content_type\": \"learning|todo|...\", \"limit\": 10}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        int days = intOf(args.get("days"), 0);
        String contentType = strOf(args.get("content_type"));

        // 按日期精确过滤（2026-09-21 联调：问"十二号弹了什么曲子"，规划器想把那天的记录全拉出来看，
        // 但工具只有 days=最近N天，表达不了"某一天"，只好拿 query 逐字匹配碰运气，0 条）。
        // date 优先于 date_from/date_to；有日期时 days 不再生效。区间含首尾两天（SQL 左闭右开，故 to+1 天）
        LocalDate from = dateOf(args.get("date_from"));
        LocalDate to = dateOf(args.get("date_to"));
        LocalDate day = dateOf(args.get("date"));
        if (day != null) {
            from = day;
            to = day;
        }
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate t = from;
            from = to;
            to = t;
        }
        boolean byDate = from != null || to != null;
        OffsetDateTime since;
        OffsetDateTime until = null;
        if (byDate) {
            since = from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime();
            until = to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        } else {
            since = days > 0
                    ? LocalDate.now(ZONE).minusDays(days).atStartOfDay(ZONE).toOffsetDateTime() : null;
        }
        // 按日期查的意图是"那天的都要"，默认给到上限，不按最近 10 条截
        int limit = Math.min(intOf(args.get("limit"), byDate ? MAX_LIMIT : 10), MAX_LIMIT);

        // moods 过滤：definition() 的 args_schema 一直对规划器宣称支持 moods，但此前执行时
        // 写死传 null——2026-09-21 联调实测 moods=["anxious"] 返回了 30 天全部 12 条（其中只有 1 条标了焦虑）
        List<String> moods = listOf(args.get("moods"));
        List<RetrievedChunkDTO> rows = searchMapper.searchStructured(
                userId, emptyToNull(contentType), moods.isEmpty() ? null : moods, toPgTextArray(moods),
                since, until, limit);

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
        // 按日期查时把区间写进 summary：回答模型据此知道"这就是那天的全部记录"，前端芯片也看得出查的是哪天
        String range = !byDate ? "" : (from != null && from.equals(to)) ? "（" + from + "）"
                : "（" + (from == null ? "…" : from) + "~" + (to == null ? "…" : to) + "）";
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + items.size() + "条" + range)
                .payload(payload)
                .build();
    }

    /** date/date_from/date_to：只认 yyyy-MM-dd（取前 10 位，容忍带时间的写法）；解析失败视为未传 */
    static LocalDate dateOf(Object o) {
        String s = strOf(o);
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** moods 参数：接受 JSON 数组或逗号分隔字符串；统一小写去空（13 情绪英文小写，同 common.proto） */
    static List<String> listOf(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object v : list) {
                String s = strOf(v);
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toLowerCase());
                }
            }
        } else if (o != null) {
            for (String s : String.valueOf(o).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim().toLowerCase());
                }
            }
        }
        return out;
    }

    /** 同 ChatServiceImpl#toPgTextArray（searchStructured 的 moodArray 参数口径） */
    static String toPgTextArray(List<String> moods) {
        if (moods == null || moods.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < moods.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(moods.get(i).replace("\"", "")).append('"');
        }
        return sb.append('}').toString();
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
