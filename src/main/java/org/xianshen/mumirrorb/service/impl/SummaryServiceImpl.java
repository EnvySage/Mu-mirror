package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.DailySummaryMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.VO.DailySummaryVO;
import org.xianshen.mumirrorb.service.SummaryService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日总结服务实现（设计文档 6.7）
 *
 * <p>每天 01:00 Asia/Shanghai 定时（DailySummaryScheduler），逐用户：</p>
 * <ol>
 *   <li>SQL 统计昨日数据（记录数、类型分布、情绪分布、活跃时段、未完成待办）</li>
 *   <li>gRPC 复用 MirrorChat.Chat（chatBlocking）生成日报文本（不新增 Proto/RPC）</li>
 *   <li>创建系统 Record（source='system'、status='done'、user_reviewed=true）——跳管道跳审核</li>
 *   <li>创建 Chunk（segment=日报文本，metadata={contentType:'daily_summary', summaryDate, ...}）</li>
 *   <li>立即 Embed → 向量入库（进 RAG 检索，对话可引用总结；失败不阻断，与 confirm 一致）</li>
 * </ol>
 *
 * <p>查询：GET /api/summaries?date=YYYY-MM-DD，按 source='system' + metadata.summaryDate。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryServiceImpl implements SummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String METADATA_CONTENT_TYPE = "daily_summary";

    private final DailySummaryMapper statsMapper;
    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final SettingsMapper settingsMapper;
    private final AiGrpcClient aiGrpcClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DailySummaryVO generateForUser(UUID userId) {
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        String summaryDate = yesterday.format(DAY);

        // 0. 幂等：该日期已有日报则跳过
        if (findBySummaryDate(userId, summaryDate) != null) {
            log.info("用户 {} 的 {} 日报已存在，跳过", userId, summaryDate);
            return null;
        }

        // 0.1 未配置 LLM 跳过
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            log.info("用户 {} 未配置 LLM，跳过每日总结", userId);
            return null;
        }

        OffsetDateTime dayStart = yesterday.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime dayEnd = yesterday.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        // 1. 昨日无有效记录则跳过（无可总结内容）
        long recordCount = statsMapper.countRecords(userId, dayStart, dayEnd);
        if (recordCount == 0) {
            log.info("用户 {} 昨日（{}）无记录，跳过每日总结", userId, summaryDate);
            return null;
        }

        // 2. 统计 + 组装日报输入 → 复用 Chat 生成（不新增 Proto/RPC）
        List<Map<String, Object>> typeStats = statsMapper.selectTypeStats(userId, dayStart, dayEnd);
        List<Map<String, Object>> moodStats = statsMapper.selectMoodStats(userId, dayStart, dayEnd);
        List<Map<String, Object>> hours = statsMapper.selectHourDistribution(userId, dayStart, dayEnd);
        List<Map<String, Object>> openTodos = statsMapper.selectOpenTodos(userId, dayStart, dayEnd);
        String summaryText = aiGrpcClient.chatBlocking(userId, buildDailyPrompt(
                summaryDate, recordCount, typeStats, moodStats, hours, openTodos));
        if (summaryText == null || summaryText.isBlank()) {
            log.warn("用户 {} 每日总结 LLM 返回空，跳过", userId);
            return null;
        }

        // 3. 系统 Record（6.7：跳管道跳审核，source='system' / status='done' / user_reviewed=true）
        Record record = Record.builder()
                .userId(userId)
                .content(summaryText)
                .source("system")
                .status(RecordStatus.DONE)
                .userReviewed(true)
                .createdAt(OffsetDateTime.now(ZONE))
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        recordMapper.insert(record);

        // 4. Chunk（metadata 用 contentType='daily_summary' + summaryDate；segment=日报全文）
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", summaryDate + " 每日总结");
        metadata.put("summary", summaryText.length() > 30 ? summaryText.substring(0, 30) : summaryText);
        metadata.put("contentType", METADATA_CONTENT_TYPE);
        metadata.put("summaryDate", summaryDate);
        metadata.put("recordCount", (int) recordCount);

        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(record.getId())
                .content(summaryText)
                .segment(summaryText)
                .metadata(metadata)
                .classifiedSegment(summaryText) // 系统内容不走审核，视为已分类
                .userEdited(false)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        chunkMapper.insert(chunk);

        // 5. 立即 Embed → 向量入库（失败不阻断：与 confirm 口径一致，向量化后续补录）
        try {
            EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(userId, summaryText);
            chunk.setEmbedding(embed.getVectorList());
            chunkMapper.updateById(chunk);
        } catch (Exception e) {
            log.warn("每日总结 Embed 失败（不阻断），用户: {}，原因: {}", userId, e.getMessage());
        }

        log.info("每日总结已生成，用户: {}, 日期: {}, 记录ID: {}", userId, summaryDate, record.getId());
        return toVO(record, chunk, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySummaryVO> list(UUID userId, String date) {
        if (date != null && !date.isBlank()) {
            // 单篇查询（含全文）
            Chunk chunk = findBySummaryDate(userId, date.trim());
            if (chunk == null) {
                return List.of();
            }
            Record record = recordMapper.selectById(chunk.getRecordId());
            if (record == null || record.getDeletedAt() != null) {
                return List.of();
            }
            return List.of(toVO(record, chunk, true));
        }

        // 全部日报（新→旧，不含全文，只给摘要行）
        List<Chunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getUserId, userId)
                        .apply("metadata->>'contentType' = {0}", METADATA_CONTENT_TYPE)
                        .orderByDesc(Chunk::getCreatedAt));
        List<DailySummaryVO> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            Record record = recordMapper.selectById(chunk.getRecordId());
            if (record == null || record.getDeletedAt() != null) {
                continue;
            }
            result.add(toVO(record, chunk, false));
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 按summaryDate查日报 Chunk（metadata.contentType='daily_summary' AND metadata.summaryDate=?）
     */
    private Chunk findBySummaryDate(UUID userId, String summaryDate) {
        List<Chunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getUserId, userId)
                        .apply("metadata->>'contentType' = {0}", METADATA_CONTENT_TYPE)
                        .apply("metadata->>'summaryDate' = {0}", summaryDate)
                        .last("LIMIT 1"));
        return chunks.isEmpty() ? null : chunks.get(0);
    }

    /**
     * 日报输入组装（question=指令文本；history 空；chunks 空）
     *
     * <p>每日总结走 Chat RPC 的 question 通道携带完整日报指令（Python chat prompt
     * 语义为"人生教练镜子"，对日报场景同样成立：基于事实陈述生成总结）。</p>
     */
    private MirrorChatProto.ChatRequest buildDailyPrompt(String summaryDate, long recordCount,
                                                         List<Map<String, Object>> typeStats,
                                                         List<Map<String, Object>> moodStats,
                                                         List<Map<String, Object>> hours,
                                                         List<Map<String, Object>> openTodos) {
        StringBuilder sb = new StringBuilder();
        sb.append("请基于以下用户 ").append(summaryDate).append(" 一天的记录数据，生成一份第一人称口吻的每日总结（200 字以内，")
                .append("包含：做了什么、情绪基调、待办遗留、一句明天的小建议）。只陈述事实，不要主观评价。\n");
        sb.append("记录数：").append(recordCount).append(" 条\n");
        sb.append("类型分布：").append(joinStats(typeStats, "type", "count")).append("\n");
        sb.append("情绪分布：").append(joinStats(moodStats, "mood", "count")).append("\n");
        sb.append("活跃时段：").append(joinStats(hours, "bucket", "count")).append("\n");
        if (!openTodos.isEmpty()) {
            sb.append("未完成待办：\n");
            for (Map<String, Object> t : openTodos) {
                sb.append("- ").append(t.get("title")).append("：").append(t.get("summary")).append("\n");
            }
        }
        return MirrorChatProto.ChatRequest.newBuilder().setQuestion(sb.toString()).build();
    }

    private String joinStats(List<Map<String, Object>> rows, String key, String valueKey) {
        if (rows == null || rows.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(row.get(key)).append("=").append(row.get(valueKey));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private DailySummaryVO toVO(Record record, Chunk chunk, boolean withContent) {
        Map<String, Object> metadata = chunk.getMetadata() == null
                ? Map.of()
                : chunk.getMetadata();
        String content = record.getContent();
        List<String> highlights = null;
        if (!withContent) {
            highlights = new ArrayList<>();
            for (String line : content.split("\n")) {
                if (!line.isBlank()) {
                    highlights.add(line.trim());
                }
                if (highlights.size() >= 3) {
                    break;
                }
            }
        }
        return DailySummaryVO.builder()
                .recordId(record.getId())
                .summaryDate((String) metadata.get("summaryDate"))
                .content(withContent ? content : null)
                .highlights(highlights)
                .stats(new LinkedHashMap<>(metadata))
                .createdAt(record.getCreatedAt())
                .build();
    }
}
