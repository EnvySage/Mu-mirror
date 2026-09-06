package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.UserMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.User;
import org.xianshen.mumirrorb.pojo.VO.ExportVO;
import org.xianshen.mumirrorb.service.ExportService;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据导出服务实现（设计文档 6.9）
 *
 * <p>全量同步导出：records(+chunks) / profile_snapshots / chat_sessions(+conversation_history)。
 * 自动排除所有 embedding 向量字段（裁决 #19：只导出不导入）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final ProfileSnapshotMapper snapshotMapper;
    private final ChatSessionMapper sessionMapper;
    private final ConversationHistoryMapper historyMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public ExportVO exportJson(UUID userId) {
        String username = resolveUsername(userId);

        // 1. records + chunks（user + system 全量；排除 embedding）
        // fix-batch C4（fix-batch-design.md B7.8）：排除 source='vault' 虚拟记录——
        // vault 全文消化借 records 表挂链 chunk，本体属资产库（vault 导出另行裁决），不进数据导出
        List<ExportVO.RecordExportItem> recordItems = new ArrayList<>();
        List<Record> records = recordMapper.selectList(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
                        .ne(Record::getSource, "vault")
                        .orderByAsc(Record::getCreatedAt));
        for (Record record : records) {
            List<Map<String, Object>> chunkItems = new ArrayList<>();
            for (Chunk chunk : chunkMapper.selectList(
                    new LambdaQueryWrapper<Chunk>().eq(Chunk::getRecordId, record.getId())
                            .orderByAsc(Chunk::getId))) {
                chunkItems.add(chunkMap(chunk));
            }
            recordItems.add(ExportVO.RecordExportItem.builder()
                    .id(record.getId())
                    .source(record.getSource())
                    .content(record.getContent())
                    .status(record.getStatus() == null ? null : record.getStatus().getValue())
                    .createdAt(format(record.getCreatedAt()))
                    .chunks(chunkItems)
                    .build());
        }

        // 2. 画像快照（排除 embedding）
        List<ExportVO.ProfileExportItem> profileItems = new ArrayList<>();
        for (ProfileSnapshot s : snapshotMapper.selectList(
                new LambdaQueryWrapper<ProfileSnapshot>()
                        .eq(ProfileSnapshot::getUserId, userId)
                        .orderByAsc(ProfileSnapshot::getCreatedAt))) {
            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("mood", s.getMoodAnalysis());
            analysis.put("learning", s.getLearningAnalysis());
            analysis.put("todo", s.getTodoAnalysis());
            analysis.put("rhythm", s.getRhythmAnalysis());
            analysis.put("overall", s.getOverallSummary());
            profileItems.add(ExportVO.ProfileExportItem.builder()
                    .snapshotType(s.getSnapshotType())
                    .analysis(analysis)
                    .userTags(s.getUserTags())
                    .createdAt(format(s.getCreatedAt()))
                    .build());
        }

        // 3. 会话 + 消息（sources 保留）
        List<ExportVO.SessionExportItem> sessionItems = new ArrayList<>();
        for (ChatSession session : sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByAsc(ChatSession::getCreatedAt))) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ConversationHistory h : historyMapper.selectBySession(session.getId(), userId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", h.getRole());
                m.put("content", h.getContent());
                m.put("sources", h.getSources());
                m.put("createdAt", format(h.getCreatedAt()));
                messages.add(m);
            }
            sessionItems.add(ExportVO.SessionExportItem.builder()
                    .title(session.getTitle())
                    .createdAt(format(session.getCreatedAt()))
                    .messages(messages)
                    .build());
        }

        log.info("数据导出完成，用户: {}, records: {}, profiles: {}, sessions: {}",
                userId, recordItems.size(), profileItems.size(), sessionItems.size());
        return ExportVO.builder()
                .version("v2")
                .exportedAt(java.time.OffsetDateTime.now().format(ISO))
                .username(username)
                .records(recordItems)
                .profiles(profileItems)
                .sessions(sessionItems)
                .build();
    }

    /**
     * chunk 导出映射：只保留业务字段（content/segment/metadata/classified_segment/user_edited），
     * embedding 一律排除（6.9）
     */
    private Map<String, Object> chunkMap(Chunk chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("segment", chunk.getSegment());
        map.put("metadata", chunk.getMetadata());
        map.put("userEdited", chunk.getUserEdited());
        map.put("createdAt", format(chunk.getCreatedAt()));
        return map;
    }

    private String resolveUsername(UUID userId) {
        // users.id 是 uuid 列：selectById(String) 会让 pgjdbc 报 uuid = character varying
        // 无法比较（E2E 修复），改用 CAST 绑定为 uuid 的查询
        Map<String, Object> row = userMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                        .select("username")
                        .apply("id = {0}::uuid", userId.toString()))
                .stream().findFirst().orElse(null);
        return row != null && row.get("username") != null ? row.get("username").toString() : null;
    }

    private String format(java.time.OffsetDateTime time) {
        return time == null ? null : time.format(ISO);
    }
}
