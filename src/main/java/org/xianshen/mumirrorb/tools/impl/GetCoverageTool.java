package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
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
 * get_coverage 工具（创新场景 B2 认知边界）：主题在记录中的时间覆盖度
 *
 * <p>"我什么时候开始…/你了解我多少" → 返回最早/最近提及、记录数、空白期——
 * 回答声明"我知道多少、从哪天开始知道"（认知边界透明 B2）。</p>
 */
@Component
@RequiredArgsConstructor
public class GetCoverageTool implements ToolExecutor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ChunkMapper chunkMapper;

    @Override
    public String name() {
        return "get_coverage";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "查询某主题在用户记录中的时间覆盖度：最早/最近提及时间、相关记录数。适合\"我从什么时候开始XX/你知道我多少\"。",
                "{\"query\": \"主题关键词\"}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        String query = SearchRecordsTool.strOf(args.get("query"));
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary(name() + ":缺少 query")
                    .payload(Map.of("error", "缺少 query 参数"))
                    .build();
        }
        String q = query.trim();
        // 全量 chunk 扫描（单用户规模 ≤ 千级，ILIKE 语义可控；不走向量——覆盖度是事实问题）
        List<Chunk> all = chunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getUserId, userId)
                .isNotNull(Chunk::getCreatedAt)
                .orderByAsc(Chunk::getCreatedAt));

        List<String> mentions = new ArrayList<>();
        for (Chunk c : all) {
            String text = (c.getSegment() == null ? "" : c.getSegment()) + " " + (c.getContent() == null ? "" : c.getContent());
            if (text.contains(q)) {
                mentions.add(c.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
            }
        }
        if (mentions.isEmpty()) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + ":「" + q + "」无记录")
                    .payload(Map.of("query", q, "covered", false,
                            "message", "用户的记录中没有出现过该主题"))
                    .build();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", q);
        payload.put("covered", true);
        payload.put("first_mention", mentions.get(0));
        payload.put("last_mention", mentions.get(mentions.size() - 1));
        payload.put("mention_days", mentions.size());
        // 空白期：首末提及之间的月数
        LocalDate first = LocalDate.parse(mentions.get(0));
        LocalDate last = LocalDate.parse(mentions.get(mentions.size() - 1));
        payload.put("span_months", java.time.temporal.ChronoUnit.MONTHS.between(first, last));
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":「" + q + "」" + mentions.size() + "天")
                .payload(payload)
                .build();
    }
}
