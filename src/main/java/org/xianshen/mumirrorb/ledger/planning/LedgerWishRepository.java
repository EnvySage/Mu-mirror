package org.xianshen.mumirrorb.ledger.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.xianshen.mumirrorb.common.exception.BusinessException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.*;

@Repository
class LedgerWishRepository {
    private static final TypeReference<Map<String, BigDecimal>> CATEGORY_BUDGETS = new TypeReference<>() {};

    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;

    LedgerWishRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.named = new NamedParameterJdbcTemplate(db);
        this.json = json;
    }

    List<Rule> lockActive(UUID owner) {
        return db.query("""
                SELECT * FROM ledger_wishes WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC, id FOR UPDATE
                """, (r, n) -> rule(r), owner);
    }

    Rule lock(UUID owner, UUID id) {
        return db.query("""
                SELECT * FROM ledger_wishes WHERE user_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE
                """, (r, n) -> rule(r), owner, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(404, "愿望卡不存在"));
    }

    void insert(UUID owner, Rule rule) {
        db.update("""
                INSERT INTO ledger_wishes
                    (id, user_id, title, link, evidence_id, price, kind, metric, start_on, window_days, window_mode,
                     target_amount, budget, category_budgets)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, rule.id(), owner, rule.title(), rule.link(), rule.evidenceId(), rule.price(), rule.kind().name(),
                rule.metric().name(), rule.startOn(), rule.windowDays(), rule.windowMode().name(), rule.targetAmount(),
                rule.budget(), encode(rule.categoryBudgets()));
    }

    void changeAmounts(UUID owner, Rule updated) {
        db.update("""
                UPDATE ledger_wishes SET target_amount = ?, budget = ?, price = ?, category_budgets = CAST(? AS jsonb)
                WHERE user_id = ? AND id = ?
                """, updated.targetAmount(), updated.budget(), updated.price(), encode(updated.categoryBudgets()), owner, updated.id());
    }

    void markChecked(UUID owner, UUID wishId, LocalDate through) {
        db.update("UPDATE ledger_wishes SET evaluated_through = ? WHERE user_id = ? AND id = ?", through, owner, wishId);
    }

    void delete(UUID owner, UUID wishId) {
        int rows = db.update("""
                UPDATE ledger_wishes SET deleted_at = NOW() WHERE user_id = ? AND id = ? AND deleted_at IS NULL
                """, owner, wishId);
        if (rows != 1) throw new BusinessException(404, "愿望卡不存在");
    }

    boolean ownsImage(UUID owner, long id) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM vault_items WHERE user_id = ? AND id = ?
                    AND deleted_at IS NULL AND mime LIKE 'image/%')
                """, Boolean.class, owner, id));
    }

    Achievement latest(UUID owner, UUID wishId) {
        return db.query("""
                SELECT * FROM ledger_wish_achievements WHERE user_id = ? AND wish_id = ?
                ORDER BY achieved_on DESC, period_start DESC LIMIT 1
                """, (r, n) -> achievement(r), owner, wishId).stream().findFirst().orElse(null);
    }

    Achievement within(UUID owner, UUID wishId, LocalDate periodStart) {
        return db.query("""
                SELECT * FROM ledger_wish_achievements WHERE user_id = ? AND wish_id = ? AND period_start = ?
                """, (r, n) -> achievement(r), owner, wishId, periodStart).stream().findFirst().orElse(null);
    }

    List<Achievement> events(UUID owner, PlanningDates.Window dates) {
        return db.query("""
                SELECT * FROM ledger_wish_achievements WHERE user_id = ? AND achieved_on BETWEEN ? AND ?
                ORDER BY achieved_on DESC, created_at DESC, id
                """, (r, n) -> achievement(r), owner, dates.start(), dates.end());
    }

    Achievement save(UUID owner, Rule rule, PlanningDates.Window window, LocalDate through, Measure measured) {
        Achievement achievement = new Achievement(UUID.randomUUID(), rule.id(), rule.title(), window.start(),
                through, through, measured.amount(), rule.targetAmount(), rule.totalBudget(),
                rule.categoryBudgets(), measured.recordIds());
        int inserted = db.update("""
                INSERT INTO ledger_wish_achievements
                    (id, user_id, wish_id, title, period_start, period_end, achieved_on, amount,
                     target_amount, budget, category_budgets, record_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                        ARRAY(SELECT jsonb_array_elements_text(CAST(? AS jsonb))::uuid))
                ON CONFLICT (user_id, wish_id, period_start) DO NOTHING
                """, achievement.id(), owner, achievement.wishId(), achievement.title(), achievement.periodStart(),
                achievement.periodEnd(), achievement.achievedOn(), achievement.amount(), achievement.targetAmount(),
                achievement.budget(), encode(achievement.categoryBudgets()), encode(achievement.recordIds()));
        return inserted == 1 ? achievement : within(owner, rule.id(), window.start());
    }

    Measure measure(UUID owner, Rule rule, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) return new Measure(BigDecimal.ZERO, List.of());
        List<String> kinds = switch (rule.metric()) {
            case SAVINGS -> List.of("EXPENSE", "RECOVERY");
            case INCOME -> List.of("INCOME");
            case BALANCE -> List.of("EXPENSE", "RECOVERY", "INCOME");
        };
        var params = new MapSqlParameterSource()
                .addValue("owner", owner).addValue("start", start).addValue("end", end).addValue("kinds", kinds);
        String categoryFilter = "";
        if (!rule.categoryBudgets().isEmpty()) {
            categoryFilter = " AND category_id IN (:categories)";
            params.addValue("categories", rule.categoryBudgets().keySet());
        }
        return named.queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE kind = 'INCOME'), 0) AS income,
                       COALESCE(SUM(amount) FILTER (WHERE kind = 'EXPENSE'), 0) AS expense,
                       COALESCE(SUM(amount) FILTER (WHERE kind = 'RECOVERY'), 0) AS recovery,
                       COALESCE(array_agg(DISTINCT record_id ORDER BY record_id), ARRAY[]::uuid[]) AS record_ids
                FROM mirror_ai.ledger_cashflows
                WHERE user_id = :owner AND kind IN (:kinds) AND occurred_on <= :end
                  AND (CASE WHEN kind = 'RECOVERY' THEN budget_day ELSE occurred_on END) BETWEEN :start AND :end
                """ + categoryFilter, params, (r, n) -> {
            BigDecimal income = r.getBigDecimal("income");
            BigDecimal netExpense = r.getBigDecimal("expense").subtract(r.getBigDecimal("recovery"));
            BigDecimal value = switch (rule.metric()) {
                case SAVINGS -> rule.totalBudget().subtract(netExpense);
                case INCOME -> income;
                case BALANCE -> income.subtract(netExpense);
            };
            return new Measure(value, uuidList(r, "record_ids"));
        });
    }

    private Rule rule(ResultSet r) throws SQLException {
        return new Rule(r.getObject("id", UUID.class), r.getString("title"), r.getString("link"),
                r.getObject("evidence_id", Long.class), r.getBigDecimal("price"), WishKind.valueOf(r.getString("kind")),
                Metric.valueOf(r.getString("metric")), r.getObject("start_on", LocalDate.class), r.getInt("window_days"),
                WindowMode.valueOf(r.getString("window_mode")), r.getBigDecimal("target_amount"), r.getBigDecimal("budget"),
                decodeBudgets(r.getString("category_budgets")), r.getObject("evaluated_through", LocalDate.class));
    }

    private Achievement achievement(ResultSet r) throws SQLException {
        return new Achievement(r.getObject("id", UUID.class), r.getObject("wish_id", UUID.class), r.getString("title"),
                r.getObject("period_start", LocalDate.class), r.getObject("period_end", LocalDate.class),
                r.getObject("achieved_on", LocalDate.class), r.getBigDecimal("amount"), r.getBigDecimal("target_amount"),
                r.getBigDecimal("budget"), decodeBudgets(r.getString("category_budgets")), uuidList(r, "record_ids"));
    }

    private static List<UUID> uuidList(ResultSet r, String column) throws SQLException {
        return Arrays.stream((Object[]) r.getArray(column).getArray())
                .map(item -> UUID.fromString(item.toString())).toList();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode ledger wish data", e);
        }
    }

    private Map<String, BigDecimal> decodeBudgets(String value) {
        try {
            return Map.copyOf(json.readValue(value, CATEGORY_BUDGETS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode ledger wish budgets", e);
        }
    }

    record Rule(UUID id, String title, String link, Long evidenceId, BigDecimal price, WishKind kind, Metric metric,
                LocalDate startOn, int windowDays, WindowMode windowMode, BigDecimal targetAmount,
                BigDecimal budget, Map<String, BigDecimal> categoryBudgets, LocalDate evaluatedThrough) {
        BigDecimal totalBudget() {
            if (budget != null) return budget;
            if (categoryBudgets.isEmpty()) return null;
            return categoryBudgets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    record Measure(BigDecimal amount, List<UUID> recordIds) {}
}
