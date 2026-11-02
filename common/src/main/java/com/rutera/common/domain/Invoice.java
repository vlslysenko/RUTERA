package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A recorded invoice (docs/DB_SCHEMA.md, group 6): SALES (receivables) or PURCHASE. The status is not stored, it
 * follows from {@link #isCancelled()}, {@link #getPaidAmount()} and the due date.
 */
@Entity
@Table(name = "invoice")
public class Invoice extends TenantEntity {

    public enum Direction {
        SALES, PURCHASE
    }

    public enum Source {
        MANUAL
    }

    public enum Status {
        OPEN, PARTIAL, PAID, OVERDUE, CANCELLED
    }

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "direction", nullable = false, length = 16)
    private Direction direction = Direction.SALES;

    @Column(name = "number", nullable = false, length = 64)
    private String number;

    @Column(name = "contractor_id", nullable = false)
    private Long contractorId;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "net", nullable = false, precision = 14, scale = 2)
    private BigDecimal net;

    @Column(name = "vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal vat;

    @Column(name = "gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal gross;

    /** Sum of the payments, kept in step by the payment service. */
    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "trip_id")
    private Long tripId;

    /** Scan or PDF; uploads come later. */
    @Column(name = "file_id")
    private Long fileId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.MANUAL;

    @Column(name = "ksef_id", length = 64)
    private String ksefId;

    @Column(name = "reminders_on", nullable = false)
    private boolean remindersOn = true;

    @Column(name = "notes", length = 2000)
    private String notes;

    /** The status on {@code today}: cancelled, paid, overdue, partially paid or open. */
    public Status statusOn(LocalDate today) {
        if (cancelled) {
            return Status.CANCELLED;
        }
        if (paidAmount.compareTo(gross) >= 0) {
            return Status.PAID;
        }
        if (dueDate.isBefore(today)) {
            return Status.OVERDUE;
        }
        return paidAmount.signum() > 0 ? Status.PARTIAL : Status.OPEN;
    }

    /** What is still to be paid (zero when cancelled or paid). */
    public BigDecimal openAmount() {
        if (cancelled) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal open = gross.subtract(paidAmount);
        return open.signum() > 0 ? open : BigDecimal.ZERO.setScale(2);
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Long getContractorId() {
        return contractorId;
    }

    public void setContractorId(Long contractorId) {
        this.contractorId = contractorId;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }

    public BigDecimal getVat() {
        return vat;
    }

    public void setVat(BigDecimal vat) {
        this.vat = vat;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public void setGross(BigDecimal gross) {
        this.gross = gross;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getKsefId() {
        return ksefId;
    }

    public void setKsefId(String ksefId) {
        this.ksefId = ksefId;
    }

    public boolean isRemindersOn() {
        return remindersOn;
    }

    public void setRemindersOn(boolean remindersOn) {
        this.remindersOn = remindersOn;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
