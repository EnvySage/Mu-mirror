package org.xianshen.mumirrorb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置（fix-batch B8：消化任务静默丢失修复）
 *
 * <p>症状（F 走查实证）：上传成功后 DigestService.digestAsync 多数情况不执行
 * （无 task-N 日志、digest_status 永远 pending），重启后偶发恢复又复发；
 * jstack 显示 executor 零存活线程但队列有任务。</p>
 *
 * <p>根因：项目只有 {@code @EnableAsync}（MuMirrorBApplication），从未显式定义
 * TaskExecutor Bean——@Async 依赖 Boot 自动装配的 applicationTaskExecutor，
 * 类路径存在特殊 Executor（grpc 等 starter）时该默认可能不被用于 @Async 或被替换，
 * 任务进入一个无人消费的队列。</p>
 *
 * <p>修法：显式定义专用线程池 vaultDigestExecutor，并在
 * {@code DigestService#digestAsync} 上用 {@code @Async("vaultDigestExecutor")}
 * 点名绑定——不再依赖任何自动装配猜测。</p>
 *
 * <ul>
 *   <li>core 2 / max 4 / 队列 64：消化是 IO 密集（PDF 抽文本可长达数秒），小池即够</li>
 *   <li>线程名前缀 digest-：jstack / 日志一眼定位（task-N 是默认名，无法归因）</li>
 *   <li>CallerRunsPolicy：队列满时任务回退到调用线程（HTTP 线程）同步执行——
 *       最坏情况是上传接口变慢，任务<b>绝不丢</b>（Abort/Discard 策略都会静默丢任务）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /**
     * vault 消化专用线程池
     */
    @Bean("vaultDigestExecutor")
    public ThreadPoolTaskExecutor vaultDigestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("digest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待队列任务跑完（最多 30s），消化中的文件状态不悬空
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("vault 消化线程池已初始化：core=2, max=4, queue=64, CallerRunsPolicy（B8）");
        return executor;
    }
}
