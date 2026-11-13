package org.xianshen.mumirrorb.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.xianshen.mumirrorb.common.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.LedgerModels.*;

@Repository
public class LedgerQueries {
    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcTemplate db;

    public LedgerQueries(JdbcTemplate db) {
        this.db = db;
    }

    public List<Category> categories(UUID owner) {
        return db.query("""
                SELECT id,name,user_id IS NULL AS preset FROM ledger_categories
                WHERE user_id IS NULL OR user_id=? ORDER BY user_id NULLS FIRST,name,id
                """, (rs, n) -> new Category(rs.getString("id"), rs.getString("name"),
                rs.getBoolean("preset")), owner);
    }

    public Entry get(UUID owner, UUID id) {
        var rows = db.query("""
                SELECT id,title,description,record_date,diary_action_id FROM ledger_records WHERE user_id=? AND id=?
                """, HEADER, owner, id);
        if (rows.isEmpty()) throw new BusinessException(404, "账目不存在");
        return entry(owner, rows.get(0), null);
    }

    public List<Entry> list(UUID owner, LocalDate start, LocalDate end, String query, Integer limit) {
        var range = range(start, end, 366);
        return headers(owner, range, query, Math.min(100, Math.max(1, limit == null ? 50 : limit)))
                .stream().map(row -> entry(owner, row, null)).toList();
    }

    public List<Entry> export(UUID owner, LocalDate start, LocalDate end) {
        var range = range(start, end, 3660);
        return headers(owner, range, "", null).stream().map(row -> entry(owner, row, range)).toList();
    }

    public List<Entry> linked(UUID owner, long diaryId) {
        return db.query("""
                SELECT r.id,r.title,r.description,r.record_date,r.diary_action_id
                FROM ledger_diary_links l JOIN ledger_records r ON r.id=l.record_id AND r.user_id=l.user_id
                WHERE l.user_id=? AND l.diary_id=? ORDER BY r.record_date DESC,r.id
                """, HEADER, owner, diaryId).stream().map(row -> entry(owner, row, null)).toList();
    }

    public List<Long> sourceIds(UUID owner, LocalDate start, LocalDate end) {
        return db.queryForList("""
                SELECT DISTINCT r.source_id FROM mirror_ai.ledger_cashflows f
                JOIN ledger_records r ON r.id=f.record_id AND r.user_id=f.user_id
                WHERE f.user_id=? AND (f.occurred_on BETWEEN ? AND ? OR f.budget_day BETWEEN ? AND ?)
                """, Long.class, owner, start, end, start, end);
    }

    public Brief brief(UUID owner, LocalDate start, LocalDate end) {
        var window = range(start, end, 366);
        var days = new LinkedHashMap<LocalDate, Totals>();
        for (var day = window.start; !day.isAfter(window.end); day = day.plusDays(1)) {
            days.put(day, new Totals());
        }
        var categories = new LinkedHashMap<String, NamedTotals>();
        db.query("""
                SELECT kind,amount,category_id,category_name,occurred_on,budget_day
                FROM mirror_ai.ledger_cashflows
                WHERE user_id=? AND (occurred_on BETWEEN ? AND ? OR budget_day BETWEEN ? AND ?)
                ORDER BY occurred_on,flow_id
                """, rs -> {
            var kind = rs.getString("kind");
            var amount = rs.getBigDecimal("amount");
            var day = rs.getObject("occurred_on", LocalDate.class);
            var budgetDay = rs.getObject("budget_day", LocalDate.class);
            var cash = days.get(day);
            if (cash != null) cash.addCash(kind, amount);
            var budget = days.get(budgetDay);
            if (budget != null && !"INCOME".equals(kind)) {
                budget.addCost(kind, amount);
                var categoryName = rs.getString("category_name");
                categories.computeIfAbsent(rs.getString("category_id"),
                        id -> new NamedTotals(categoryName, new Totals())).totals.addCash(kind, amount);
            }
        }, owner, window.start, window.end, window.start, window.end);
        var total = new Totals();
        days.values().forEach(total::sum);
        var categoryLines = categories.entrySet().stream().map(row -> {
            var data = row.getValue();
            return new CategoryTotal(row.getKey(), data.name, data.totals.expense, data.totals.recovery,
                    data.totals.expense.subtract(data.totals.recovery));
        }).toList();
        var dayLines = days.entrySet().stream().map(row -> {
            var t = row.getValue();
            return new DayTotal(row.getKey(), t.expense, t.income, t.recovery, t.net, t.count);
        }).toList();
        return new Brief(window.start, window.end, total.expense, total.income, total.recovery,
                total.net, total.count, categoryLines, dayLines);
    }

    private List<Header> headers(UUID owner, DateRange range, String query, Integer limit) {
        var search = query == null ? "" : query.strip();
        if (search.length() > 120) throw new BusinessException(400, "查询词过长");
        var sql = """
                SELECT r.id,r.title,r.description,r.record_date,r.diary_action_id
                FROM ledger_records r JOIN ledger_flows f ON f.record_id=r.id AND f.user_id=r.user_id
                WHERE r.user_id=? AND f.occurred_on BETWEEN ? AND ?
                    AND (?='' OR strpos(lower(r.title || ' ' || r.description || ' ' || f.note), lower(?))>0)
                GROUP BY r.id ORDER BY max(f.occurred_on) DESC,r.created_at DESC,r.id
                """;
        var args = new ArrayList<Object>(List.of(owner, range.start, range.end, search, search));
        if (limit != null) {
            sql += " LIMIT ?";
            args.add(limit);
        }
        return db.query(sql, HEADER, args.toArray());
    }

    private Entry entry(UUID owner, Header row, DateRange range) {
        var sql = """
                SELECT f.id,f.kind,f.amount,COALESCE(original.category_id,f.category_id) AS category_id,
                       c.name AS category_name,f.occurred_on,f.note,f.related_expense_id,f.evidence_id
                FROM ledger_flows f
                LEFT JOIN ledger_flows original ON original.id=f.related_expense_id AND original.user_id=f.user_id
                JOIN ledger_categories c ON c.id=COALESCE(original.category_id,f.category_id)
                WHERE f.user_id=? AND f.record_id=?
                """;
        var args = new ArrayList<Object>(List.of(owner, row.id));
        if (range != null) {
            sql += " AND f.occurred_on BETWEEN ? AND ?";
            args.add(range.start);
            args.add(range.end);
        }
        var flows = db.query(sql + " ORDER BY f.occurred_on,f.position,f.created_at,f.id", FLOW, args.toArray());
        var diaries = db.queryForList("""
                SELECT l.diary_id FROM ledger_diary_links l JOIN records d ON d.id=l.diary_id AND d.user_id=l.user_id
                WHERE l.user_id=? AND l.record_id=? AND d.deleted_at IS NULL ORDER BY l.diary_id
                """, Long.class, owner, row.id);
        var total = new Totals();
        flows.forEach(flow -> total.addCash(flow.kind(), flow.amount()));
        return new Entry(row.id, row.title, row.description, row.recordDate, total.expense, total.income,
                total.recovery, total.expense.subtract(total.recovery), flows, diaries, row.diaryActionId);
    }

    static DateRange range(LocalDate start, LocalDate end, int maxDays) {
        LocalDate last = end == null ? LedgerService.today() : end;
        LocalDate first = start == null ? last.minusDays(29) : start;
        var length = ChronoUnit.DAYS.between(first, last);
        if (length < 0 || length >= maxDays) throw new BusinessException(400, "日期范围无效或过长");
        return new DateRange(first, last);
    }

    private static final RowMapper<Header> HEADER = (rs, n) -> new Header(rs.getObject("id", UUID.class),
            rs.getString("title"), rs.getString("description"), rs.getObject("record_date", LocalDate.class),
            rs.getObject("diary_action_id", UUID.class));
    private static final RowMapper<Flow> FLOW = (rs, n) -> new Flow(rs.getObject("id", UUID.class),
            rs.getString("kind"), rs.getBigDecimal("amount"), rs.getString("category_id"), rs.getString("category_name"),
            rs.getObject("occurred_on", LocalDate.class), rs.getString("note"),
            rs.getObject("related_expense_id", UUID.class), rs.getObject("evidence_id", Long.class));

    record DateRange(LocalDate start, LocalDate end) {}
    private record Header(UUID id, String title, String description, LocalDate recordDate, UUID diaryActionId) {}
    private record NamedTotals(String name, Totals totals) {}

    private static final class Totals {
        private BigDecimal expense = ZERO;
        private BigDecimal income = ZERO;
        private BigDecimal recovery = ZERO;
        private BigDecimal net = ZERO;
        private int count;

        void addCash(String kind, BigDecimal value) {
            switch (kind) {
                case "EXPENSE" -> expense = expense.add(value);
                case "INCOME" -> income = income.add(value);
                case "RECOVERY" -> recovery = recovery.add(value);
                default -> throw new IllegalArgumentException("Unknown ledger flow kind");
            }
            count++;
        }

        void addCost(String kind, BigDecimal value) {
            net = "EXPENSE".equals(kind) ? net.add(value) : net.subtract(value);
        }

        void sum(Totals other) {
            expense = expense.add(other.expense);
            income = income.add(other.income);
            recovery = recovery.add(other.recovery);
            net = net.add(other.net);
            count += other.count;
        }
    }
}
