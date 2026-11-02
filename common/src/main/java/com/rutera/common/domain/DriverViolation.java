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
 * A violation of Regulation (EC) 561/2006 found in a driver's tachograph data, with the severity of Regulation (EU)
 * 2016/403. Recomputed after every import touching the driver; the acknowledgement stays with the same
 * (driver, rule, start). Values are minutes.
 */
@Entity
@Table(name = "driver_violation")
public class DriverViolation extends TenantEntity {

    public enum Rule {
        DAILY_DRIVING_9H, DAILY_DRIVING_10H, WEEKLY_DRIVING_56H, TWO_WEEK_DRIVING_90H, BREAK_45_AFTER_4H30,
        DAILY_REST_11H, DAILY_REST_9H, WEEKLY_REST_45H, WEEKLY_REST_24H
    }

    public enum Severity {
        MINOR, SERIOUS, VERY_SERIOUS, MOST_SERIOUS
    }

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "rule", nullable = false, length = 32)
    private Rule rule;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "actual_value", nullable = false)
    private int actualValue;

    @Column(name = "limit_value", nullable = false)
    private int limitValue;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "ack_by")
    private Long ackBy;

    @Column(name = "ack_at")
    private Instant ackAt;

    @Column(name = "ack_note", length = 500)
    private String ackNote;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Rule getRule() {
        return rule;
    }

    public void setRule(Rule rule) {
        this.rule = rule;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public int getActualValue() {
        return actualValue;
    }

    public void setActualValue(int actualValue) {
        this.actualValue = actualValue;
    }

    public int getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(int limitValue) {
        this.limitValue = limitValue;
    }

    public String getVehiclePlate() {
        return vehiclePlate;
    }

    public void setVehiclePlate(String vehiclePlate) {
        this.vehiclePlate = vehiclePlate;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Long getAckBy() {
        return ackBy;
    }

    public void setAckBy(Long ackBy) {
        this.ackBy = ackBy;
    }

    public Instant getAckAt() {
        return ackAt;
    }

    public void setAckAt(Instant ackAt) {
        this.ackAt = ackAt;
    }

    public String getAckNote() {
        return ackNote;
    }

    public void setAckNote(String ackNote) {
        this.ackNote = ackNote;
    }
}
