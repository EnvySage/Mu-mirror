package org.xianshen.mumirrorb.agent;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.AgentProperties;

import java.util.UUID;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AgentLifecycleTest {

    @Test
    void leaseLossStopsRenewalAndCancelsRunOnce() throws Exception {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentProperties properties = new AgentProperties();
        properties.setHeartbeatSeconds(1);
        properties.setLeaseSeconds(3);
        when(repository.renewLease(any(), anyInt())).thenReturn(false);
        UUID runId = UUID.randomUUID();
        CountDownLatch lost = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AgentLifecycleService lifecycle = new AgentLifecycleService(repository, properties);

        try {
            lifecycle.startLease(runId, () -> { callbacks.incrementAndGet(); lost.countDown(); });
            assertTrue(lost.await(3, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, callbacks.get());
            verify(repository).renewLease(runId, 3);
            lifecycle.sweepExpiredRuns();
            verify(repository).abandonExpired();
            ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(repository).deleteExpired(cutoff.capture());
            assertTrue(Duration.between(cutoff.getValue(), OffsetDateTime.now().minusDays(30))
                    .abs().toMillis() < 1_000);
        } finally {
            lifecycle.close();
        }
    }
}
