package com.rutera.core.finance;

import com.rutera.common.domain.InvoiceLine;
import com.rutera.common.domain.InvoicePayment;
import com.rutera.common.domain.ReminderSent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Invoices, payments, debts and reminders (contract of 2026-11-02). */
public final class FinanceDto {

    private FinanceDto() {
    }

    public record Ref(Long id, String name) {
    }

    public record NumberRef(Long id, String number) {
    }

    public record Invoice(Long id, com.rutera.common.domain.Invoice.Direction direction, String number,
                          Ref contractor, LocalDate issueDate, LocalDate dueDate, String currency, BigDecimal net,
                          BigDecimal vat, BigDecimal gross, BigDecimal paidAmount, BigDecimal openAmount,
                          com.rutera.common.domain.Invoice.Status status, int daysOverdue, List<NumberRef> orders,
                          NumberRef trip, boolean remindersOn, Instant lastReminderAt,
                          com.rutera.common.domain.Invoice.Source source, String notes, Instant createdAt,
                          Instant updatedAt) {
    }

    public record Line(Long id, int seq, String description, BigDecimal qty, String unit, BigDecimal unitPrice,
                       InvoiceLine.VatCode vatCode, BigDecimal vatRate, BigDecimal net, BigDecimal vat,
                       BigDecimal gross) {
    }

    public record Payment(Long id, LocalDate paidAt, BigDecimal amount, String currency, InvoicePayment.Method method,
                          String reference, String note, Instant createdAt) {
    }

    public record Reminder(Long id, Integer offsetDays, Instant sentAt, List<String> toEmails, String subject,
                           ReminderSent.Status status, String error, Instant createdAt) {
    }

    /** {@code InvoiceDetail}: the invoice (unwrapped) with lines, payments and reminders. */
    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped Invoice invoice, List<Line> lines,
                         List<Payment> payments, List<Reminder> reminders) {
    }

    public record Total(String currency, BigDecimal gross, BigDecimal open) {
    }

    public record Page(List<Invoice> items, long total, List<Total> totals) {
    }

    public record LineInput(
            @Size(max = 500) String description,
            @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal qty,
            @Size(max = 16) String unit,
            @Digits(integer = 10, fraction = 4) BigDecimal unitPrice,
            String vatCode,
            @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal vatRate) {
    }

    /**
     * Body of {@code POST}/{@code PUT /app/invoices[/{id}]} (PUT is a full replacement). With lines the totals are
     * computed; without lines net and gross are required (vat = gross - net when missing). A sales invoice without
     * a number gets the next one of the month ({@code FV/2026/11/001}); a purchase invoice needs the supplier's.
     */
    public record InvoiceInput(
            String direction,
            @Size(max = 64) String number,
            Long contractorId,
            LocalDate issueDate,
            LocalDate dueDate,
            @Size(max = 3) String currency,
            @Valid @Size(max = 200) List<LineInput> lines,
            @Digits(integer = 12, fraction = 2) BigDecimal net,
            @Digits(integer = 12, fraction = 2) BigDecimal vat,
            @Digits(integer = 12, fraction = 2) BigDecimal gross,
            List<Long> orderIds,
            Long tripId,
            Boolean remindersOn,
            Boolean cancelled,
            @Size(max = 2000) String notes) {
    }

    public record PaymentInput(
            LocalDate paidAt,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            String method,
            @Size(max = 128) String reference,
            @Size(max = 500) String note) {
    }

    public record Buckets(BigDecimal notDue, BigDecimal d1to30, BigDecimal d31to60, BigDecimal d61to90,
                          BigDecimal over90) {
    }

    public record DebtRow(Ref contractor, String currency, BigDecimal open, BigDecimal overdue, int invoices,
                          LocalDate oldestDueDate, int maxDaysOverdue, Buckets buckets) {
    }

    public record Step(int offsetDays, boolean escalate) {
    }

    public record Schedule(int sendHour, List<String> ccEmails, boolean stopWhenPaid, List<Step> steps) {
    }

    /** A step of {@link ScheduleInput}: offsetDays required (-60..365, unique), escalate false by default. */
    public record StepInput(Integer offsetDays, Boolean escalate) {
    }

    /** Body of {@code PUT /app/reminders/schedule}; without steps the default plan's steps. */
    public record ScheduleInput(Integer sendHour, @Size(max = 5) List<String> ccEmails, Boolean stopWhenPaid,
                                @Size(max = 10) List<StepInput> steps) {
    }

    static Ref ref(Long id, String name) {
        return id == null ? null : new Ref(id, name);
    }
}
