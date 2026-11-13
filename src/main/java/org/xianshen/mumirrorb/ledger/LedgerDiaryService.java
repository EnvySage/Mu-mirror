package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.LedgerModels.Entry;

@Service
class LedgerDiaryService {
    private final JdbcTemplate db;
    private final RecordMapper records;
    private final ChunkMapper chunks;
    private final RecordService recordService;
    private final ChunkEmbeddingJobService embedding;
    private final SnapshotLineageService lineage;
    private final ObjectProvider<ActionProposalService> proposals;
    private final ObjectMapper json;

    LedgerDiaryService(JdbcTemplate db, RecordMapper records, ChunkMapper chunks, RecordService recordService,
                       ChunkEmbeddingJobService embedding, SnapshotLineageService lineage,
                       ObjectProvider<ActionProposalService> proposals, ObjectMapper json) {
        this.db = db;
        this.records = records;
        this.chunks = chunks;
        this.recordService = recordService;
        this.embedding = embedding;
        this.lineage = lineage;
        this.proposals = proposals;
        this.json = json;
    }

    void create(UUID owner, Entry entry, String mode, String rawDiary) {
        if ("TEMPLATE".equals(mode)) {
            createTemplate(owner, entry);
            return;
        }
        var input = new RecordDTO();
        input.setContent(rawDiary.strip());
        var source = recordService.create(input, owner);
        link(owner, entry.id(), source.getId(), "AI");
        var payload = json.createObjectNode().put("sourceRecordId", source.getId()).put("mode", "AI_ASSISTED");
        var action = proposals.getObject().prepare(owner,
                new ActionPrepareDTO("record.organize", payload, "ledger-diary-" + entry.id(), null, null));
        db.update("UPDATE ledger_records SET diary_action_id=? WHERE id=? AND user_id=?",
                action.getId(), entry.id(), owner);
    }

    private void createTemplate(UUID owner, Entry entry) {
        var now = OffsetDateTime.now(LedgerService.ZONE);
        var content = template(entry);
        var record = Record.builder().userId(owner).content(content).source("user").status(RecordStatus.DONE)
                .aiProcessingAuthorized(true).processingVersion(1).indexPolicyVersion(1).memoryState(RecordMemoryState.READY)
                .userReviewed(true).createdAt(now).updatedAt(now).build();
        records.insert(record);
        var chunk = Chunk.builder().userId(owner).recordId(record.getId()).content(content).segment(content)
                .classifiedSegment(content).metadata(metadata(entry, content)).aiMemoryApproved(true)
                .todoApproved(false).userEdited(false).contentVersion(1L).embeddingEnabled(false).embeddingStatus("not_requested")
                .createdAt(now).build();
        chunks.insert(chunk);
        link(owner, entry.id(), record.getId(), "TEMPLATE");
    }

    void refreshTemplates(UUID owner, Entry entry) {
        var content = template(entry);
        for (var diaryId : templateIds(owner, entry.id())) {
            var record = records.selectOwnedForUpdate(owner, diaryId);
            if (record == null || content.equals(record.getContent())) continue;
            var ids = db.queryForList("""
                    SELECT id FROM chunks WHERE user_id=? AND record_id=?
                      AND metadata->>'ledgerRecordId'=?
                    """, Long.class, owner, diaryId, entry.id().toString());
            if (ids.size() != 1) continue;
            var chunk = chunks.selectById(ids.get(0));
            boolean enabled = record.getMemoryState() != RecordMemoryState.DISABLED;
            db.update("""
                    UPDATE records SET content=?,updated_at=NOW(),processing_version=processing_version+1,
                        memory_state=?,index_policy_version=1,memory_failure_reason=NULL WHERE id=? AND user_id=?
                    """, content, enabled ? "READY" : "DISABLED", diaryId, owner);
            embedding.cancel(chunk.getId(), owner);
            db.update("""
                    UPDATE chunks SET content=?,segment=?,classified_segment=?,metadata=CAST(? AS jsonb),
                        embedding=NULL,embedding_error=NULL,embedding_enabled=FALSE,embedding_status=?,ai_memory_approved=?,
                        content_version=content_version+1 WHERE id=? AND user_id=?
                    """, content, content, content, json.valueToTree(metadata(entry, content)).toString(),
                    "not_requested", enabled, chunk.getId(), owner);
            lineage.invalidateSource(owner, "chunk", chunk.getId(), "ledger_updated");
            lineage.invalidateSource(owner, "record", diaryId, "ledger_updated");
        }
    }

    void removeTemplates(UUID owner, UUID ledgerId) {
        for (var diaryId : templateIds(owner, ledgerId)) {
            // Only the template created for this entry is derived. Authored diaries keep their text.
            if (Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM records WHERE id=? AND user_id=? AND deleted_at IS NULL)",
                    Boolean.class, diaryId, owner))) recordService.softDelete(diaryId, owner);
        }
    }

    void requireDiary(UUID owner, long diaryId) {
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM records WHERE id=? AND user_id=? AND deleted_at IS NULL AND source='user')
                """, Boolean.class, diaryId, owner))) throw new BusinessException(404, "日记不存在");
    }

    private List<Long> templateIds(UUID owner, UUID entryId) {
        return db.queryForList("""
                SELECT diary_id FROM ledger_diary_links WHERE user_id=? AND record_id=? AND source='TEMPLATE'
                ORDER BY diary_id
                """, Long.class, owner, entryId);
    }

    private void link(UUID owner, UUID id, long diary, String source) {
        db.update("INSERT INTO ledger_diary_links(record_id,user_id,diary_id,source) VALUES (?,?,?,?)",
                id, owner, diary, source);
    }

    static String template(Entry entry) {
        var lines = new ArrayList<String>();
        lines.add(entry.recordDate() + " · " + entry.title());
        if (!entry.description().isBlank()) lines.add(entry.description());
        lines.add("付出 ¥" + entry.expense().toPlainString() + "，收回 ¥" + entry.recovery().toPlainString()
                + "，收入 ¥" + entry.income().toPlainString() + "，净花 ¥" + entry.netExpense().toPlainString());
        for (var flow : entry.flows()) {
            var kind = switch (flow.kind()) {
                case "EXPENSE" -> "支出";
                case "RECOVERY" -> "收回";
                default -> "收入";
            };
            lines.add(flow.occurredOn() + " " + flow.categoryName() + " " + kind + " ¥"
                    + flow.amount().toPlainString() + (flow.note().isBlank() ? "" : " · " + flow.note()));
        }
        var text = String.join("\n", lines);
        return text.length() <= 2000 ? text : text.substring(0, 1989) + "\n查看账目明细";
    }

    private static Map<String, Object> metadata(Entry entry, String content) {
        return Map.of("title", entry.title(), "summary", content, "contentType", "note",
                "keywords", List.of("记账"), "ledgerRecordId", entry.id().toString());
    }
}
