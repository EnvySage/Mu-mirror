package org.xianshen.mumirrorb.ledger.planning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
class LedgerPlanningScheduler {
    private final JdbcTemplate db;
    private final LedgerWishService wishes;
    private final LedgerRecurringService recurring;

    @Scheduled(cron = "10 0 0 * * *", zone = PlanningDates.BUSINESS_ZONE)
    void midnight() {
        List<UUID> recurringOwners = db.queryForList("""
                SELECT DISTINCT user_id FROM ledger_recurring_rules
                WHERE deleted_at IS NULL AND next_due_on <= ?
                """, UUID.class, PlanningDates.today());
        recurringOwners.forEach(owner -> reconcile("recurring bills", () -> recurring.generate(owner)));

        List<UUID> wishOwners = db.queryForList("""
                SELECT DISTINCT w.user_id FROM ledger_wishes w
                WHERE w.deleted_at IS NULL AND w.start_on < ?
                  AND (w.kind = 'RECURRING' OR NOT EXISTS (
                    SELECT 1 FROM ledger_wish_achievements a WHERE a.user_id = w.user_id AND a.wish_id = w.id
                  ))
                """, UUID.class, PlanningDates.today());
        wishOwners.forEach(owner -> reconcile("wishes", () -> wishes.refresh(owner)));
    }

    private static void reconcile(String action, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Ledger midnight reconciliation failed for {}", action, e);
        }
    }
}
