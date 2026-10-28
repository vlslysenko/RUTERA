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
 * Outgoing e-mail queue (replaces the JMS queue of SMDP cloud): the business transaction inserts a row,
 * a scheduled job renders the Thymeleaf template and sends it with retries.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox extends TenantEntity {

    public enum Status { QUEUED, SENDING, SENT, FAILED }

    @Column(name = "to_emails", nullable = false, length = 1000)
    private String toEmails;

    @Column(name = "cc_emails", length = 1000)
    private String ccEmails;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "template_key", nullable = false, length = 64)
    private String templateKey;

    /** Template parameters as JSON, including the language. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private String params = "{}";

    /** JSON array of stored_file ids. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments")
    private String attachments;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.QUEUED;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    public String getToEmails() {
        return toEmails;
    }

    public void setToEmails(String toEmails) {
        this.toEmails = toEmails;
    }

    public String getCcEmails() {
        return ccEmails;
    }

    public void setCcEmails(String ccEmails) {
        this.ccEmails = ccEmails;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
