package com.rutera.common.repository;

import com.rutera.common.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /**
     * Invoices matching the filters (null = any): {@code from}/{@code to} bound the issue date, {@code q} is a
     * lower-case LIKE pattern on the number and the contractor's name. Newest issue date first. Status filters are
     * applied by the service (the status depends on today's date).
     */
    @Query("""
            select i from Invoice i
            where (:direction is null or i.direction = :direction)
              and (:contractorId is null or i.contractorId = :contractorId)
              and (:from is null or i.issueDate >= :from)
              and (:to is null or i.issueDate <= :to)
              and (:q is null or lower(i.number) like :q
                   or exists (select c.id from Contractor c where c.id = i.contractorId and lower(c.name) like :q))
            order by i.issueDate desc, i.id desc""")
    List<Invoice> search(@Param("direction") Invoice.Direction direction, @Param("contractorId") Long contractorId,
                         @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("q") String q);

    Optional<Invoice> findByDirectionAndNumber(Invoice.Direction direction, String number);

    /** Numbers of the direction starting with {@code prefix} (for the next number of a series). */
    @Query("select i.number from Invoice i where i.direction = :direction and i.number like concat(:prefix, '%')")
    List<String> numbersStartingWith(@Param("direction") Invoice.Direction direction, @Param("prefix") String prefix);

    /** Open sales invoices with reminders on (for the daily reminder run). */
    @Query("""
            select i from Invoice i
            where i.direction = com.rutera.common.domain.Invoice.Direction.SALES and i.cancelled = false
              and i.remindersOn = true and i.paidAmount < i.gross""")
    List<Invoice> openSalesWithReminders();
}
