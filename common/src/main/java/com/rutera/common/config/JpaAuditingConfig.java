package com.rutera.common.config;

import com.rutera.common.context.RequestContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code created_at / created_by / updated_at / updated_by} of {@link com.rutera.common.domain.BaseEntity}.
 * The acting user comes from {@link RequestContext}; system jobs and the first-start bootstrap leave it empty.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> auditorAware() {
        return RequestContext::userId;
    }
}
