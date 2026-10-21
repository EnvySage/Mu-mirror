package org.xianshen.mumirrorb.agent;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.config.AgentProperties;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Per-run lease renewal and bounded retention cleanup. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLifecycleService {

    private final AgentRunRepository repository;
    private final AgentProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "agent-run-lease");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> leases = new ConcurrentHashMap<>();

    public void startLease(UUID runId, Runnable onLeaseLost) {
        stopLease(runId);
        long every = Math.max(1, properties.getHeartbeatSeconds());
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!repository.renewLease(runId, properties.getLeaseSeconds())) {
                    stopLease(runId);
                    onLeaseLost.run();
                }
            } catch (Exception error) {
                log.warn("Agent run lease renewal failed: runId={}, cause={}", runId, error.getMessage());
                stopLease(runId);
                onLeaseLost.run();
            }
        }, every, every, TimeUnit.SECONDS);
        leases.put(runId, future);
    }

    @jakarta.annotation.PostConstruct
    public void sweepAtStartup() {
        sweepExpiredRuns();
    }

    public void stopLease(UUID runId) {
        ScheduledFuture<?> future = leases.remove(runId);
        if (future != null) future.cancel(false);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepExpiredRuns() {
        try {
            int abandoned = repository.abandonExpired();
            int deleted = repository.deleteExpired(OffsetDateTime.now().minusDays(30));
            if (abandoned > 0 || deleted > 0) {
                log.info("Agent run lifecycle sweep: abandoned={}, deleted={}", abandoned, deleted);
            }
        } catch (Exception error) {
            log.warn("Agent run lifecycle sweep failed: {}", error.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        leases.values().forEach(future -> future.cancel(false));
        leases.clear();
        scheduler.shutdownNow();
    }
}
