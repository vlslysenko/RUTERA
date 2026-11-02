package com.rutera.common.repository;

import com.rutera.common.domain.InvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {

    List<InvoicePayment> findByInvoiceIdOrderByPaidAtAscIdAsc(Long invoiceId);
}
