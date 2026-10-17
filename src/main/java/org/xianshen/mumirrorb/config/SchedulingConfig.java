package org.xianshen.mumirrorb.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled in normal application runs and can be disabled for offline maintenance commands.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
