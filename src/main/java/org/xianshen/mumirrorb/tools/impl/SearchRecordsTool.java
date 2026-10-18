package org.xianshen.mumirrorb.tools.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
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
 * 日记记录检索：由 Agent 根据问题选择结构化过滤、语义检索或组合检索。
 */
@Component
@RequiredArgsConstructor
public class SearchRecordsTool implements ToolExecutor {

    @Override
    public org.xianshen.mumirrorb.tools.ToolEffect effect() { return org.xianshen.mumirrorb.tools.ToolEffect.READ; }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LIMIT = 20;
    private static final double DEFAULT_HALF_LIFE = 30.0;

    private final ChatSearchMapper searchMapper;
    private final AiGrpcClient aiGrpcClient;
    private final SettingsMapper settingsMapper;
    private final MirrorProperties mirrorProperties;

    @Override
    public String name() {
        return "search_records";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "检索用户已授权的日记片段。按问题选择 mode：structured 用日期/情绪/类型等明确条件；"
                        + "semantic 用自然语言含义找记录；hybrid 在语义检索上叠加明确过滤条件。"
                        + "semantic/hybrid 的 query 写用户问题的核心意思，不要求复用原文词语。"
                        + "问某一天或明确日期范围时填写 date/date_from/date_to；问画像快照使用 get_profile。",
                "{\"mode\": \"structured|semantic|hybrid（必填）\", \"query\": \"自然语言检索意图（语义/组合检索必填）\", "
                        + "\"date\": \"2026-09-12（可选）\", \"date_from\": \"2026-09-01（可选）\", "
                        + "\"date_to\": \"2026-09-07（可选，含当天）\", \"days\": 7, "
                        + "\"moods\": [\"happy\"], \"content_type\": \"learning|todo|...\", \"limit\": 10}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        args = args == null ? Map.of() : args;
        int days = intOf(args.get("days"), 0);
        String contentType = strOf(args.get("content_type"));
        String query = strOf(args.get("query"));
        String requestedMode = strOf(args.get("mode"));
        boolean explicitMode = requestedMode != null && !requestedMode.isBlank();

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
        String normalizedType = emptyToNull(contentType);
        boolean hasFilters = normalizedType != null || !moods.isEmpty() || byDate || days > 0;
        String mode = normalizeMode(requestedMode, query, hasFilters);
        List<String> normalizedMoods = moods.isEmpty() ? null : moods;
        List<RetrievedChunkDTO> rows;
        if ("structured".equals(mode)) {
            rows = searchMapper.searchStructured(userId, normalizedType, normalizedMoods,
                    toPgTextArray(moods), since, until, limit);
        } else {
            String vector = embedQueryVector(userId, query);
            Double maxDistance = mirrorProperties.getRagMaxCosineDistance() <= 0
                    ? null : mirrorProperties.getRagMaxCosineDistance();
            boolean decay = !byDate;
            double halfLife = halfLife(userId);
            if ("semantic".equals(mode)) {
                rows = searchMapper.searchSemantic(userId, vector, decay, halfLife, maxDistance, limit);
            } else {
                rows = searchMapper.searchHybrid(userId, vector, normalizedType, normalizedMoods,
                        toPgTextArray(moods), since, until, decay, halfLife, maxDistance, limit);
            }
        }

        // 文件正文与元信息由 find_item/recall_item 提供 [F] 来源卡；search_records 只回日记记录，
        // 避免把 vault 资产伪装成可点击的日记来源。
        rows = rows.stream().filter(r -> r.getVaultItemId() == null).toList();

        if ("structured".equals(mode) && query != null && !query.isBlank()) {
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
            if (r.getScore() != null) {
                m.put("score", r.getScore());
            }
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
                .summary(name() + ":" + items.size() + "条"
                        + (explicitMode ? "（" + mode + "）" : "") + range)
                .payload(payload)
                .build();
    }

    private String normalizeMode(String requested, String query, boolean hasFilters) {
        String mode = requested == null ? "structured" : requested.trim().toLowerCase();
        if (!List.of("structured", "semantic", "hybrid").contains(mode)) {
            mode = "structured";
        }
        if (!"structured".equals(mode) && (query == null || query.isBlank())) {
            return "structured";
        }
        // 语义查询与明确过滤条件同时出现时，切到可叠加过滤条件的 SQL + 向量路径。
        return "semantic".equals(mode) && hasFilters ? "hybrid" : mode;
    }

    private String embedQueryVector(UUID userId, String query) {
        EmbeddingProto.EmbedResponse response = aiGrpcClient.embed(userId, query.trim());
        if (response.getVectorCount() == 0) {
            throw new IllegalStateException("Embedding 服务未返回检索向量");
        }
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < response.getVectorCount(); i++) {
            if (i > 0) vector.append(',');
            vector.append(response.getVector(i));
        }
        return vector.append(']').toString();
    }

    private double halfLife(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        Integer days = settings == null ? null : settings.getRagHalfLife();
        return days == null || days <= 0 ? DEFAULT_HALF_LIFE : days;
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
