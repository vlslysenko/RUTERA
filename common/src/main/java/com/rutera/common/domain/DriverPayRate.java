package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pay rates of a driver from a date on; the newest rate not after a day applies to it. */
@Entity
@Table(name = "driver_pay_rate")
public class DriverPayRate extends TenantEntity {

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "monthly_base", precision = 14, scale = 2)
    private BigDecimal monthlyBase;

    @Column(name = "per_diem_home", precision = 14, scale = 2)
    private BigDecimal perDiemHome;

    @Column(name = "per_diem_abroad", precision = 14, scale = 2)
    private BigDecimal perDiemAbroad;

    @Column(name = "per_km", precision = 10, scale = 4)
    private BigDecimal perKm;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public BigDecimal getMonthlyBase() {
        return monthlyBase;
    }

    public void setMonthlyBase(BigDecimal monthlyBase) {
        this.monthlyBase = monthlyBase;
    }

    public BigDecimal getPerDiemHome() {
        return perDiemHome;
    }

    public void setPerDiemHome(BigDecimal perDiemHome) {
        this.perDiemHome = perDiemHome;
    }

    public BigDecimal getPerDiemAbroad() {
        return perDiemAbroad;
    }

    public void setPerDiemAbroad(BigDecimal perDiemAbroad) {
        this.perDiemAbroad = perDiemAbroad;
    }

    public BigDecimal getPerKm() {
        return perKm;
    }

    public void setPerKm(BigDecimal perKm) {
        this.perKm = perKm;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
