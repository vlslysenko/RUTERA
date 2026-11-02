package com.rutera.common.repository;

import com.rutera.common.domain.ReminderSent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface ReminderSentRepository extends JpaRepository<ReminderSent, Long> {

    List<ReminderSent> findByInvoiceIdOrderByIdDesc(Long invoiceId);

    List<ReminderSent> findByInvoiceIdIn(Collection<Long> invoiceIds);

    boolean existsByInvoiceIdAndOffsetDays(Long invoiceId, Integer offsetDays);

    Optional<ReminderSent> findByOutboxId(Long outboxId);
}
