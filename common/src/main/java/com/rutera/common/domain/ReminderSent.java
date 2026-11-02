package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A payment reminder of an invoice; {@link #getOffsetDays()} is the plan step (days from the due date), null for a
 * reminder sent by hand. The e-mail itself goes through {@link EmailOutbox}.
 */
@Entity
@Table(name = "reminder_sent")
public class ReminderSent extends TenantEntity {

    public enum Status {
        QUEUED, SENT, FAILED
    }

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "offset_days")
    private Integer offsetDays;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Comma-separated addresses. */
    @Column(name = "to_emails", nullable = false, length = 1000)
    private String toEmails;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.QUEUED;

    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "error", length = 500)
    private String error;

    public Long getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    public Integer getOffsetDays() {
        return offsetDays;
    }

    public void setOffsetDays(Integer offsetDays) {
        this.offsetDays = offsetDays;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public String getToEmails() {
        return toEmails;
    }

    public void setToEmails(String toEmails) {
        this.toEmails = toEmails;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getOutboxId() {
        return outboxId;
    }

    public void setOutboxId(Long outboxId) {
        this.outboxId = outboxId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
