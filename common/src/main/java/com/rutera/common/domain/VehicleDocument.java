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
 * Document of a vehicle with a deadline (registration, insurance, inspection, tachograph calibration...).
 * Within {@link #getRemindDays()} days before {@link #getValidTo()} it needs attention. Deleted physically.
 */
@Entity
@Table(name = "vehicle_document")
public class VehicleDocument extends TenantEntity {

    public enum Kind {
        REGISTRATION, INSURANCE_OC, INSURANCE_AC, INSPECTION, TACHO_CALIBRATION, LICENSE_COPY, TOLL_DEVICE, OTHER
    }

    public static final int DEFAULT_REMIND_DAYS = 30;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

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

    @Column(name = "cost", precision = 14, scale = 2)
    private BigDecimal cost;

    /** ISO 4217, set together with {@link #getCost()}. */
    @Column(name = "currency", length = 3)
    private String currency;

    /** Scan in stored_file; uploads come later. */
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "remind_days", nullable = false)
    private int remindDays = DEFAULT_REMIND_DAYS;

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
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

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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
