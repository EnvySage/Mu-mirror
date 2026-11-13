package org.xianshen.mumirrorb.ledger.planning;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.ledger.LedgerModels.EntryInput;
import org.xianshen.mumirrorb.ledger.LedgerModels.FlowInput;
import org.xianshen.mumirrorb.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.*;

@Service
@RequiredArgsConstructor
public class LedgerRecurringService {
    private static final int MAX_CATCH_UP_PER_RULE = 366;
    private static final String DUE_SELECT = """
            SELECT o.id, o.rule_id, o.amount, o.due_on, o.state, o.record_id, r.title, r.category_id, r.note
            FROM ledger_recurring_occurrences o
            JOIN ledger_recurring_rules r ON r.user_id = o.user_id AND r.id = o.rule_id
            """;
    private static final RowMapper<RecurringRule> RULE = (r, n) -> new RecurringRule(
            r.getObject("id", UUID.class), r.getString("title"), r.getBigDecimal("amount"),
            r.getString("category_id"), r.getObject("start_on", LocalDate.class),
            Interval.valueOf(r.getString("interval_unit")), r.getInt("interval_count"), r.getString("note"));
    private static final RowMapper<DueBill> DUE = (r, n) -> new DueBill(
            r.getObject("id", UUID.class), r.getObject("rule_id", UUID.class), r.getString("title"),
            r.getBigDecimal("amount"), r.getString("category_id"), r.getObject("due_on", LocalDate.class),
            r.getString("note"), DueState.valueOf(r.getString("state")), r.getObject("record_id", UUID.class));

    private final JdbcTemplate db;
    private final LedgerService ledger;

    public List<RecurringRule> list(UUID user) {
        return db.query("""
                SELECT * FROM ledger_recurring_rules WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC, id
                """, RULE, user);
    }

    @Transactional
    public RecurringRule create(UUID user, RecurringInput input) {
        LocalDate today = PlanningDates.today();
        if (input == null || input.interval() == null || input.intervalCount() < 1 || input.intervalCount() > 366) {
            throw new BusinessException(400, "请选择有效的周期与间隔");
        }
        String category = input.categoryId() == null || input.categoryId().isBlank() ? "other" : input.categoryId();
        if (ledger.categories(user).stream().noneMatch(c -> c.id().equals(category))) {
            throw new BusinessException(400, "账目分类不存在");
        }
        RecurringRule rule = new RecurringRule(UUID.randomUUID(), PlanningValidation.title(input.title()),
                PlanningValidation.money(input.amount(), false), category, PlanningValidation.start(input.startOn(), today, 0),
                input.interval(), input.intervalCount(), PlanningValidation.note(input.note()));
        db.update("""
                INSERT INTO ledger_recurring_rules
                    (id, user_id, title, amount, category_id, start_on, interval_unit, interval_count, note, next_due_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rule.id(), user, rule.title(), rule.amount(), rule.categoryId(), rule.startOn(),
                rule.interval().name(), rule.intervalCount(), rule.note(), rule.startOn());
        materialize(user, today);
        return rule;
    }

    @Transactional
    public void delete(UUID user, UUID id) {
        int updated = db.update("""
                UPDATE ledger_recurring_rules SET deleted_at = NOW()
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, id, user);
        if (updated != 1) throw new BusinessException(404, "周期账单不存在");
        db.update("""
                UPDATE ledger_recurring_occurrences SET state = 'SKIPPED', decided_at = NOW()
                WHERE rule_id = ? AND user_id = ? AND state = 'PENDING'
                """, id, user);
    }

    @Transactional
    public List<DueBill> pending(UUID user) {
        materialize(user, PlanningDates.today());
        return db.query(DUE_SELECT + """
                WHERE o.user_id = ? AND o.state = 'PENDING' AND r.deleted_at IS NULL
                ORDER BY o.due_on, o.id
                """, DUE, user);
    }

    @Transactional
    public DueBill confirm(UUID user, UUID id, ConfirmBill input) {
        DueBill bill = findForUpdate(user, id);
        BigDecimal amount = input == null || input.amount() == null ? bill.amount()
                : PlanningValidation.money(input.amount(), false);
        if (bill.state() == DueState.CONFIRMED) {
            if (amount.compareTo(bill.amount()) != 0) throw new BusinessException(409, "这期账单已用其他金额确认");
            return bill;
        }
        if (bill.state() != DueState.PENDING) throw new BusinessException(409, "这期账单已跳过");
        LocalDate today = PlanningDates.today();
        FlowInput flow = new FlowInput(null, "EXPENSE", amount, bill.categoryId(), today, bill.note(), null, null);
        UUID recordId = ledger.create(user, new EntryInput(id, bill.title(), bill.note(), List.of(flow), "NONE", null)).id();
        db.update("""
                UPDATE ledger_recurring_occurrences SET state = 'CONFIRMED', amount = ?, record_id = ?, decided_at = NOW()
                WHERE id = ? AND user_id = ?
                """, amount, recordId, id, user);
        return withDecision(bill, amount, DueState.CONFIRMED, recordId);
    }

    @Transactional
    public DueBill skip(UUID user, UUID id) {
        DueBill bill = findForUpdate(user, id);
        if (bill.state() == DueState.SKIPPED) return bill;
        if (bill.state() != DueState.PENDING) throw new BusinessException(409, "这期账单已入账");
        db.update("""
                UPDATE ledger_recurring_occurrences SET state = 'SKIPPED', decided_at = NOW()
                WHERE id = ? AND user_id = ?
                """, id, user);
        return withDecision(bill, bill.amount(), DueState.SKIPPED, null);
    }

    @Transactional
    public void generate(UUID user) {
        materialize(user, PlanningDates.today());
    }

    private void materialize(UUID user, LocalDate today) {
        List<ScheduledRule> rules = db.query("""
                SELECT * FROM ledger_recurring_rules
                WHERE user_id = ? AND deleted_at IS NULL AND next_due_on <= ? ORDER BY id
                FOR UPDATE
                """, (r, n) -> new ScheduledRule(RULE.mapRow(r, n), r.getLong("next_index"),
                r.getObject("next_due_on", LocalDate.class)), user, today);
        for (ScheduledRule scheduled : rules) {
            RecurringRule rule = scheduled.rule();
            long nextIndex = scheduled.nextIndex();
            LocalDate dueOn = scheduled.nextDueOn();
            for (int generated = 0; !dueOn.isAfter(today) && generated < MAX_CATCH_UP_PER_RULE; generated++) {
                db.update("""
                        INSERT INTO ledger_recurring_occurrences (id, user_id, rule_id, due_on, amount)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, rule_id, due_on) DO NOTHING
                        """, UUID.randomUUID(), user, rule.id(), dueOn, rule.amount());
                dueOn = PlanningDates.dueDate(rule.startOn(), rule.interval(), rule.intervalCount(), ++nextIndex);
            }
            db.update("""
                    UPDATE ledger_recurring_rules SET next_index = ?, next_due_on = ? WHERE user_id = ? AND id = ?
                    """, nextIndex, dueOn, user, rule.id());
        }
    }

    private DueBill findForUpdate(UUID user, UUID id) {
        return db.query(DUE_SELECT + "WHERE o.user_id = ? AND o.id = ? FOR UPDATE OF o", DUE, user, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(404, "待确认账单不存在"));
    }

    private static DueBill withDecision(DueBill bill, BigDecimal amount, DueState state, UUID recordId) {
        return new DueBill(bill.id(), bill.ruleId(), bill.title(), amount, bill.categoryId(), bill.dueOn(),
                bill.note(), state, recordId);
    }

    private record ScheduledRule(RecurringRule rule, long nextIndex, LocalDate nextDueOn) {}
}
