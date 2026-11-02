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

/** A payment to a driver for a period (YYYY-MM); visible to the owner and the accountant only. */
@Entity
@Table(name = "driver_payment")
public class DriverPayment extends TenantEntity {

    public enum Kind {
        SALARY, PER_DIEM, BONUS, ADVANCE, DEDUCTION;

        /** Advances and deductions reduce what is still to be paid. */
        public boolean subtracts() {
            return this == ADVANCE || this == DEDUCTION;
        }
    }

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    /** YYYY-MM. */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "note", length = 500)
    private String note;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDate paidAt) {
        this.paidAt = paidAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
