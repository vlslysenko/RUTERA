package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * Document of a driver with a deadline (licence, code 95, medical and psychological tests, ADR, passport, visa,
 * work permit). Within {@link #getRemindDays()} days before {@link #getValidTo()} it needs attention. Deleted
 * physically.
 */
@Entity
@Table(name = "driver_document")
public class DriverDocument extends TenantEntity {

    public enum Kind {
        LICENSE, CPC_CODE95, MEDICAL, PSYCHO, ADR, PASSPORT, VISA, WORK_PERMIT, OTHER
    }

    public static final int DEFAULT_REMIND_DAYS = 30;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "number", length = 64)
    private String number;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    /** Scan in stored_file; uploads come later. */
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "remind_days", nullable = false)
    private int remindDays = DEFAULT_REMIND_DAYS;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public int getRemindDays() {
        return remindDays;
    }

    public void setRemindDays(int remindDays) {
        this.remindDays = remindDays;
    }
}
