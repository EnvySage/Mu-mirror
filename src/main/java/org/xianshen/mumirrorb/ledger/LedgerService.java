package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.LedgerModels.*;

@Service
public class LedgerService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> KINDS = Set.of("EXPENSE", "INCOME", "RECOVERY");
    private static final Set<String> DIARY_MODES = Set.of("NONE", "TEMPLATE", "AI");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99");

    private final JdbcTemplate db;
    private final LedgerQueries queries;
    private final LedgerDiaryService diaries;
    private final ObjectMapper json;
    private final SnapshotLineageService lineage;

    public LedgerService(JdbcTemplate db, LedgerQueries queries, LedgerDiaryService diaries, ObjectMapper json,
                         SnapshotLineageService lineage) {
        this.db = db;
        this.queries = queries;
        this.diaries = diaries;
        this.json = json;
        this.lineage = lineage;
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public List<Category> categories(UUID owner) {
        return queries.categories(owner);
    }

    @Transactional
    public Category addCategory(UUID owner, String name) {
        String label = text(name, 60, "分类");
        if (label.isEmpty()) throw bad("请填写分类名");
        if (categories(owner).stream().anyMatch(category -> category.name().equalsIgnoreCase(label))) {
            throw new BusinessException(409, "已存在同名分类");
        }
        String id = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO ledger_categories(id,user_id,name) VALUES (?,?,?)", id, owner, label);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "已存在同名分类");
        }
        return new Category(id, label, false);
    }

    @Transactional
    public Entry create(UUID owner, EntryInput input) {
        if (input == null) throw bad("请填写账目");
        lineage.lockUserLineage(owner);
        var id = UUID.randomUUID();
        var previous = claim(owner, input.requestId(), id, "create", input);
        if (previous != null) return queries.get(owner, previous);
        var now = today();
        var flows = normalize(owner, input.flows(), List.of(), Map.of(), now);
        var mode = mode(input.diaryMode(), input.diaryText());
        var title = text(input.title(), 120, "账目标题");
        if (title.isEmpty()) title = category(owner, flows.get(0).categoryId).name();
        var description = text(input.description(), 1000, "记账描述");
        db.update("INSERT INTO ledger_records(id,user_id,title,description,record_date) VALUES (?,?,?,?,?)",
                id, owner, title, description, now);
        writeFlows(owner, id, flows);
        if (!"NONE".equals(mode)) {
            diaries.create(owner, queries.get(owner, id), mode, input.diaryText());
        }
        return queries.get(owner, id);
    }

    @Transactional
    public Entry append(UUID owner, UUID id, AppendInput input) {
        if (input == null) throw bad("请填写流水");
        lineage.lockUserLineage(owner);
        var previous = claim(owner, input.requestId(), id, "append:" + id, input);
        if (previous != null) return queries.get(owner, previous);
        var current = lockEntry(owner, id);
        var flows = normalize(owner, input.flows(), current.flows(), Map.of(), today());
        writeFlows(owner, id, flows);
        diaries.refreshTemplates(owner, queries.get(owner, id));
        invalidate(owner, id);
        return queries.get(owner, id);
    }

    @Transactional
    public Entry update(UUID owner, UUID id, EntryInput input) {
        if (input == null) throw bad("请填写账目");
        lineage.lockUserLineage(owner);
        var entry = lockEntry(owner, id);
        var now = today();
        var editable = new HashMap<UUID, Flow>();
        var history = new ArrayList<Flow>();
        for (Flow flow : entry.flows()) {
            if (now.equals(flow.occurredOn())) editable.put(flow.id(), flow);
            else history.add(flow);
        }
        if (editable.isEmpty()) throw bad("只能修改当天流水");
        var flows = normalize(owner, input.flows(), history, editable, now);
        var title = input.title() == null ? entry.title() : text(input.title(), 120, "账目标题");
        var description = input.description() == null ? entry.description() : text(input.description(), 1000, "记账描述");
        if (title.isEmpty()) throw bad("请填写账目标题");
        if (!entry.recordDate().equals(now)
                && (!entry.title().equals(title) || !entry.description().equals(description))) {
            throw bad("历史账目标题和描述只读");
        }
        db.update("UPDATE ledger_records SET title=?,description=? WHERE user_id=? AND id=?",
                title, description, owner, id);
        var kept = new HashSet<UUID>();
        flows.forEach(flow -> kept.add(flow.id));
        var removed = editable.values().stream().filter(flow -> !kept.contains(flow.id())).toList();
        // Break old references before removing an expense or applying a new relationship.
        db.update("""
                UPDATE ledger_flows SET related_expense_id=NULL
                WHERE user_id=? AND record_id=? AND occurred_on=? AND kind='RECOVERY'
                """, owner, id, now);
        removed.forEach(flow -> db.update("DELETE FROM ledger_flows WHERE id=? AND user_id=?",
                flow.id(), owner));
        writeFlows(owner, id, flows);
        diaries.refreshTemplates(owner, queries.get(owner, id));
        invalidate(owner, id);
        return queries.get(owner, id);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        lineage.lockUserLineage(owner);
        var entry = lockEntry(owner, id);
        var now = today();
        if (!entry.recordDate().equals(now) || entry.flows().stream().anyMatch(flow -> !now.equals(flow.occurredOn()))) {
            throw bad("只能删除完全属于当天的账目");
        }
        diaries.removeTemplates(owner, id);
        invalidate(owner, id);
        db.update("DELETE FROM ledger_flows WHERE user_id=? AND record_id=? AND kind='RECOVERY'", owner, id);
        db.update("DELETE FROM ledger_records WHERE user_id=? AND id=?", owner, id);
    }

    @Transactional
    public void deleteFlow(UUID owner, UUID id, UUID flowId) {
        lineage.lockUserLineage(owner);
        var entry = lockEntry(owner, id);
        var flow = entry.flows().stream().filter(item -> item.id().equals(flowId)).findFirst()
                .orElseThrow(() -> new BusinessException(404, "流水不存在"));
        if (!today().equals(flow.occurredOn())) throw bad("只能删除当天流水");
        if (entry.flows().size() == 1) {
            delete(owner, id);
            return;
        }
        if (entry.flows().stream().anyMatch(item -> flowId.equals(item.relatedExpenseId()))) {
            throw bad("请先删除或调整关联的回款");
        }
        db.update("DELETE FROM ledger_flows WHERE user_id=? AND record_id=? AND id=?", owner, id, flowId);
        diaries.refreshTemplates(owner, queries.get(owner, id));
        invalidate(owner, id);
    }

    @Transactional
    public List<Entry> replaceDiaryLinks(UUID owner, long diaryId, List<UUID> recordIds) {
        diaries.requireDiary(owner, diaryId);
        if (recordIds == null || recordIds.size() > 100 || recordIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw bad("关联账目列表无效");
        }
        var ids = new LinkedHashSet<>(recordIds);
        // Retain the origin of generated diary links when the selection is unchanged.
        ids.forEach(id -> queries.get(owner, id));
        db.queryForList("SELECT record_id FROM ledger_diary_links WHERE user_id=? AND diary_id=?",
                UUID.class, owner, diaryId).stream().filter(id -> !ids.contains(id)).forEach(id ->
                db.update("DELETE FROM ledger_diary_links WHERE user_id=? AND diary_id=? AND record_id=?",
                        owner, diaryId, id));
        ids.forEach(id -> db.update("""
                INSERT INTO ledger_diary_links(record_id,user_id,diary_id) VALUES (?,?,?)
                ON CONFLICT (record_id,diary_id) DO NOTHING
                """, id, owner, diaryId));
        return queries.linked(owner, diaryId);
    }

    public List<Entry> diaryLinks(UUID owner, long diaryId) {
        diaries.requireDiary(owner, diaryId);
        return queries.linked(owner, diaryId);
    }

    public void validateDraft(UUID owner, EntryInput input, UUID appendTo) {
        if (input == null || input.flows() == null || input.flows().isEmpty() || input.flows().size() > 50) {
            throw bad("请至少填写一笔流水，单次最多 50 笔");
        }
        text(input.title(), 120, "账目标题");
        text(input.description(), 1000, "记账描述");
        mode(input.diaryMode(), input.diaryText());
        if (appendTo != null) queries.get(owner, appendTo);
        for (FlowInput flow : input.flows()) {
            if (flow == null) throw bad("流水格式无效");
            if (flow.kind() != null && !KINDS.contains(flow.kind())) throw bad("请选择有效的收支方向");
            if (flow.amount() != null) money(flow.amount());
            if (flow.categoryId() != null) category(owner, flow.categoryId());
            text(flow.note(), 500, "流水备注");
            validateEvidence(owner, flow.evidenceId());
        }
    }

    private List<WriteFlow> normalize(UUID owner, List<FlowInput> input, List<Flow> retained,
                                      Map<UUID, Flow> editable, LocalDate now) {
        if (input == null || input.isEmpty() || input.size() > 50) throw bad("请填写 1 到 50 笔流水");
        var writes = new ArrayList<WriteFlow>();
        var all = new HashMap<UUID, WriteFlow>();
        for (var flow : retained) {
            all.put(flow.id(), new WriteFlow(flow.id(), flow.kind(), flow.amount(), flow.categoryId(),
                    flow.occurredOn(), flow.note(), flow.relatedExpenseId(), flow.evidenceId(), 0));
        }
        for (FlowInput flow : input) {
            if (flow == null || flow.kind() == null || !KINDS.contains(flow.kind())) throw bad("请选择收支方向");
            var day = flow.occurredOn() == null ? now : flow.occurredOn();
            if (!now.equals(day)) throw bad("只能记当天发生的账，历史流水只读");
            if (flow.id() != null && !editable.containsKey(flow.id())) throw bad("流水不属于本次编辑");
            var id = flow.id() == null ? UUID.randomUUID() : flow.id();
            var categoryId = flow.categoryId() == null || flow.categoryId().isBlank() ? "other" : flow.categoryId();
            category(owner, categoryId);
            validateEvidence(owner, flow.evidenceId());
            var write = new WriteFlow(id, flow.kind(), money(flow.amount()), categoryId, day,
                    text(flow.note(), 500, "流水备注"), flow.relatedExpenseId(), flow.evidenceId(), writes.size());
            if (all.putIfAbsent(id, write) != null) throw bad("同一流水不能提交两次");
            writes.add(write);
        }
        var expenses = all.values().stream().filter(flow -> "EXPENSE".equals(flow.kind)).toList();
        var returns = new HashMap<UUID, BigDecimal>();
        for (var row : all.values()) {
            if (!"RECOVERY".equals(row.kind) && row.relatedExpenseId != null) throw bad("仅收回款可以关联原支出");
            if (row.relatedExpenseId != null && (!all.containsKey(row.relatedExpenseId)
                    || !"EXPENSE".equals(all.get(row.relatedExpenseId).kind))) {
                throw bad("收回款须关联这条账目内仍存在的支出");
            }
            if (row.relatedExpenseId != null) returns.merge(row.relatedExpenseId, row.amount, BigDecimal::add);
        }
        var result = new ArrayList<WriteFlow>();
        for (var row : writes) {
            var originalId = row.relatedExpenseId;
            if (originalId == null && "RECOVERY".equals(row.kind) && expenses.size() == 1) {
                originalId = expenses.get(0).id;
                returns.merge(originalId, row.amount, BigDecimal::add);
            }
            var categoryId = originalId == null ? row.categoryId : all.get(originalId).categoryId;
            result.add(new WriteFlow(row.id, row.kind, row.amount, categoryId, row.occurredOn,
                    row.note, originalId, row.evidenceId, row.position));
        }
        returns.forEach((id, total) -> {
            if (total.compareTo(all.get(id).amount) > 0) throw bad("关联回款总额不能超过原支出");
        });
        return result;
    }

    private void writeFlows(UUID owner, UUID id, List<WriteFlow> flows) {
        for (var kind : List.of("EXPENSE", "INCOME", "RECOVERY")) {
            for (var flow : flows) {
                if (!kind.equals(flow.kind)) continue;
                db.update("""
                        INSERT INTO ledger_flows(id,record_id,user_id,kind,amount,category_id,occurred_on,note,
                            related_expense_id,evidence_id,position) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT (id) DO UPDATE SET kind=EXCLUDED.kind,amount=EXCLUDED.amount,
                            category_id=EXCLUDED.category_id,note=EXCLUDED.note,
                            related_expense_id=EXCLUDED.related_expense_id,evidence_id=EXCLUDED.evidence_id,
                            position=EXCLUDED.position
                        WHERE ledger_flows.record_id=EXCLUDED.record_id AND ledger_flows.user_id=EXCLUDED.user_id
                        """, flow.id, id, owner, flow.kind, flow.amount, flow.categoryId, flow.occurredOn,
                        flow.note, flow.relatedExpenseId, flow.evidenceId, flow.position);
            }
        }
    }

    private Entry lockEntry(UUID owner, UUID id) {
        var rows = db.queryForList("SELECT id FROM ledger_records WHERE user_id=? AND id=? FOR UPDATE",
                UUID.class, owner, id);
        if (rows.isEmpty()) throw new BusinessException(404, "账目不存在");
        return queries.get(owner, id);
    }

    private void invalidate(UUID owner, UUID id) {
        var source = db.queryForObject("SELECT source_id FROM ledger_records WHERE user_id=? AND id=?",
                Long.class, owner, id);
        lineage.invalidateSource(owner, "ledger", source, "ledger_updated");
    }

    private Category category(UUID owner, String id) {
        return db.query("""
                SELECT id,name,user_id IS NULL AS preset FROM ledger_categories
                WHERE id=? AND (user_id IS NULL OR user_id=?)
                """, (rs, n) -> new Category(rs.getString("id"), rs.getString("name"), rs.getBoolean("preset")),
                id, owner).stream().findFirst().orElseThrow(() -> bad("请选择有效的账目分类"));
    }

    private void validateEvidence(UUID owner, Long evidence) {
        if (evidence == null) return;
        var exists = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM vault_items WHERE id=? AND user_id=? AND deleted_at IS NULL)
                """, Boolean.class, evidence, owner);
        if (!Boolean.TRUE.equals(exists)) throw bad("付款凭证不存在");
    }

    private UUID claim(UUID owner, UUID key, UUID id, String operation, Object payload) {
        if (key == null) throw bad("缺少本次保存标识");
        var fingerprint = fingerprint(operation, payload);
        if (db.update("""
                INSERT INTO ledger_write_receipts(user_id,request_id,record_id,fingerprint)
                VALUES (?,?,?,?) ON CONFLICT (user_id,request_id) DO NOTHING
                """, owner, key, id, fingerprint) == 1) return null;
        return db.queryForObject("""
                SELECT record_id,fingerprint FROM ledger_write_receipts WHERE user_id=? AND request_id=?
                """, (rs, n) -> {
            if (!fingerprint.equals(rs.getString("fingerprint"))) {
                throw new BusinessException(409, "这个保存标识已用于不同内容，请重新提交");
            }
            return rs.getObject("record_id", UUID.class);
        }, owner, key);
    }

    private String fingerprint(String operation, Object payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(List.of(operation, payload))));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("无法生成保存标识", e);
        }
    }

    private static String mode(String mode, String diaryText) {
        String value = mode == null ? "NONE" : mode;
        if (!DIARY_MODES.contains(value)) throw bad("无效的日记选项");
        var content = text(diaryText, 1500, "日记");
        if ("AI".equals(value) && content.isEmpty()) return "TEMPLATE";
        return value;
    }

    public static BigDecimal money(BigDecimal input) {
        if (input == null || input.signum() <= 0 || input.compareTo(MAX_AMOUNT) > 0) {
            throw bad("请输入有效金额");
        }
        try {
            return input.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw bad("金额最多两位小数");
        }
    }

    private static String text(String input, int max, String label) {
        var value = input == null ? "" : input.strip();
        if (value.length() > max) throw bad(label + "不能超过 " + max + " 字");
        return value;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(400, message);
    }

    private record WriteFlow(UUID id, String kind, BigDecimal amount, String categoryId, LocalDate occurredOn,
                             String note, UUID relatedExpenseId, Long evidenceId, int position) {}
}
