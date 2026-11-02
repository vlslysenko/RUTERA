package com.rutera.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} jobs (the e-mail outbox every minute, the payment reminder plans every hour).
 * {@code rutera.scheduling.enabled=false} switches them off; the tests do that and call the jobs directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "rutera.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
