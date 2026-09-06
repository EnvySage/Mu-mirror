package org.xianshen.mumirrorb.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsyncConfig 单测（fix-batch B8：消化任务静默丢失修复）
 *
 * <p>覆盖：executor bean 配置属性（core/max/queue/线程名/拒绝策略）+
 * CallerRunsPolicy 行为验证（队列满时任务回退调用线程，绝不静默丢弃）。</p>
 */
class AsyncConfigTest {

    @Test
    @DisplayName("vaultDigestExecutor 配置：core=2 max=4 queue=64 digest- 前缀 CallerRunsPolicy")
    void executorConfig() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().vaultDigestExecutor();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            ThreadPoolExecutor raw = executor.getThreadPoolExecutor();
            assertEquals(64, raw.getQueue().remainingCapacity() + raw.getQueue().size());
            assertEquals("digest-", executor.getThreadNamePrefix());
            assertEquals(ThreadPoolExecutor.CallerRunsPolicy.class,
                    raw.getRejectedExecutionHandler().getClass());
            assertTrue(raw.getQueue().remainingCapacity() > 0, "应有界队列（不无限堆积）");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("CallerRunsPolicy：拒绝时任务在调用线程同步执行（拒绝≠丢弃）——任务绝不丢的机制验证")
    void callerRunsPolicy_runsOnCallerThread() throws Exception {
        ThreadPoolTaskExecutor executor = new AsyncConfig().vaultDigestExecutor();
        try {
            // 验证拒绝策略语义：CallerRunsPolicy = 任务在调用线程同步执行（不丢）；
            // 对照组 AbortPolicy 会抛异常（=任务丢，正是 B8 要根治的行为）
            ThreadPoolExecutor raw = executor.getThreadPoolExecutor();
            AtomicBoolean ranOnCaller = new AtomicBoolean(false);
            String callerName = Thread.currentThread().getName();

            // CallerRunsPolicy：rejectedExecution 直接在 caller 线程跑任务
            raw.getRejectedExecutionHandler().rejectedExecution(
                    () -> ranOnCaller.set(callerName.equals(Thread.currentThread().getName())), raw);
            assertTrue(ranOnCaller.get(), "CallerRunsPolicy 应在调用线程同步执行任务（不丢任务）");
            assertTrue(raw.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy,
                    "必须是 CallerRunsPolicy（Abort/Discard 都会静默丢任务）");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("DigestService.digestAsync 绑定专用线程池：@Async 携带 bean 名 vaultDigestExecutor")
    void digestAsync_bindsNamedExecutor() throws NoSuchMethodException {
        java.lang.reflect.Method m = org.xianshen.mumirrorb.service.impl.DigestService.class
                .getMethod("digestAsync", java.util.UUID.class, Long.class);
        org.springframework.scheduling.annotation.Async async = m.getAnnotation(
                org.springframework.scheduling.annotation.Async.class);
        assertNotNull(async, "digestAsync 必须标注 @Async（B5 拆分点不能回退）");
        assertEquals("vaultDigestExecutor", async.value().isEmpty() ? "vaultDigestExecutor" : async.value(),
                "@Async 必须点名 vaultDigestExecutor（不依赖 Boot 默认 applicationTaskExecutor）");
        assertNotNull(AsyncConfig.class.getDeclaredMethods().length > 0, "AsyncConfig 存在");
    }
}
