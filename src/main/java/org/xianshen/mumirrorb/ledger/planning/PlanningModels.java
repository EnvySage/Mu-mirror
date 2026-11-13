package org.xianshen.mumirrorb.ledger.planning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlanningModels {
    private PlanningModels() {}

    public enum Interval { DAY, WEEK, MONTH }
    public enum WishKind { ONCE, RECURRING }
    public enum Metric { SAVINGS, INCOME, BALANCE }
    public enum WindowMode { FIXED, ROLLING }
    public enum WishState { NOT_STARTED, ACTIVE, ACHIEVED }
    public enum DueState { PENDING, CONFIRMED, SKIPPED }

    public record RecurringInput(String title, BigDecimal amount, String categoryId, LocalDate startOn,
                                 Interval interval, int intervalCount, String note) {}

    public record RecurringRule(UUID id, String title, BigDecimal amount, String categoryId, LocalDate startOn,
                                Interval interval, int intervalCount, String note) {}

    public record DueBill(UUID id, UUID ruleId, String title, BigDecimal amount, String categoryId,
                          LocalDate dueOn, String note, DueState state, UUID recordId) {}

    public record ConfirmBill(BigDecimal amount) {}

    public record WishInput(String title, String link, Long evidenceId, BigDecimal price, WishKind kind, Metric metric,
                            LocalDate startOn, int windowDays, WindowMode windowMode, BigDecimal targetAmount,
                            BigDecimal budget, Map<String, BigDecimal> categoryBudgets) {}

    public record WishView(UUID id, String title, String link, Long evidenceId, BigDecimal price, WishKind kind, Metric metric,
                           LocalDate startOn, int windowDays, WindowMode windowMode, BigDecimal targetAmount,
                           BigDecimal budget, Map<String, BigDecimal> categoryBudgets, WishState state,
                           BigDecimal progressAmount, BigDecimal progressRatio, LocalDate periodStart,
                           LocalDate periodEnd, Achievement achievement) {}

    public record Achievement(UUID id, UUID wishId, String title, LocalDate periodStart, LocalDate periodEnd,
                              LocalDate achievedOn, BigDecimal amount, BigDecimal targetAmount, BigDecimal budget,
                              Map<String, BigDecimal> categoryBudgets, List<UUID> recordIds) {}
}
