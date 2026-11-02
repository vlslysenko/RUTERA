package com.rutera.common.repository;

import com.rutera.common.domain.EmailOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Tenant-filtered like every business repository; the sender reads the queue of all firmas as root. Top level:
 * Spring Data does not register the nested interfaces of {@link SystemRepositories}.
 */
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    List<EmailOutbox> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            EmailOutbox.Status status, Instant now);
}
