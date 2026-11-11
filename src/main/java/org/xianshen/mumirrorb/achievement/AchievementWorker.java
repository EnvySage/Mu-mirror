package org.xianshen.mumirrorb.achievement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AchievementWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AchievementWorker.class);
    private final JdbcTemplate db;
    private final AchievementService service;
    private final TransactionTemplate transactions;
    private volatile boolean ready;

    public AchievementWorker(JdbcTemplate db, AchievementService service, org.springframework.transaction.PlatformTransactionManager manager) {
        this.db=db; this.service=service; this.transactions=new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        service.installCatalog();
        ready=true;
    }
    @Scheduled(fixedDelayString="${mirror.achievements.worker-delay-ms:2000}")
    public void tick() {
        if (!ready) return;
        try { drain(); }
        catch (Exception error) { LOG.error("Achievement worker failed; transaction will retry",error); }
    }
    public void drain() {
        AtomicReference<List<Long>> claimed=new AtomicReference<>(List.of());
        try {
          transactions.executeWithoutResult(status -> {
            // Single-user batches avoid cross-user lock order cycles. Row locks recover on crash.
            var users = db.queryForList("""
                SELECT user_id FROM achievement_event WHERE processed_at IS NULL AND available_at<=now()
                ORDER BY id LIMIT 1
                """,UUID.class);
            if (users.isEmpty()) return;
            UUID user=users.get(0);
            var events=db.queryForList("""
                SELECT id FROM achievement_event WHERE user_id=? AND processed_at IS NULL AND available_at<=now()
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
                """,Long.class,user);
            if (events.isEmpty()) return;
            claimed.set(events);
            service.reconcile(user);
            for (Long id : events) db.update("UPDATE achievement_event SET processed_at=now(),attempts=attempts+1 WHERE id=?",id);
          });
        } catch (RuntimeException error) {
            // Retry state must survive the rollback, and a poison user must not starve others.
            transactions.executeWithoutResult(status -> {
                for (Long id : claimed.get()) db.update("""
                    UPDATE achievement_event SET attempts=attempts+1,
                    available_at=now()+least(300,power(2,least(attempts+1,8))) * interval '1 second',
                    last_error=? WHERE id=? AND processed_at IS NULL
                    """,error.getClass().getSimpleName(),id);
            });
            throw error;
        }
    }
    @Scheduled(fixedDelayString="${mirror.achievements.cleanup-delay-ms:3600000}")
    public void pruneProcessedEvents() {
        if (!ready) return;
        db.update("""
            DELETE FROM achievement_event WHERE id IN (
              SELECT id FROM achievement_event WHERE processed_at<now()-interval '7 days'
              ORDER BY id LIMIT 5000)
            """);
    }
}
