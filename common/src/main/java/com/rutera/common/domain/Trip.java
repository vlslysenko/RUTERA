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
 * Trip of a vehicle (docs/DB_SCHEMA.md, group 5): crew in {@link TripDriver}, stops in {@link TripStop}, the orders
 * it carries point at it ({@code transport_order.trip_id}).
 */
@Entity
@Table(name = "trip")
public class Trip extends TenantEntity {

    public enum Status {
        PLANNED, RUNNING, DONE, CANCELLED
    }

    @Column(name = "number", nullable = false, length = 32)
    private String number;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "trailer_id")
    private Long trailerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.PLANNED;

    @Column(name = "planned_start")
    private Instant plannedStart;

    @Column(name = "planned_end")
    private Instant plannedEnd;

    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @Column(name = "km_planned")
    private Integer kmPlanned;

    @Column(name = "km_actual")
    private Integer kmActual;

    @Column(name = "notes", length = 2000)
    private String notes;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Long getTrailerId() {
        return trailerId;
    }

    public void setTrailerId(Long trailerId) {
        this.trailerId = trailerId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getPlannedStart() {
        return plannedStart;
    }

    public void setPlannedStart(Instant plannedStart) {
        this.plannedStart = plannedStart;
    }

    public Instant getPlannedEnd() {
        return plannedEnd;
    }

    public void setPlannedEnd(Instant plannedEnd) {
        this.plannedEnd = plannedEnd;
    }

    public Instant getActualStart() {
        return actualStart;
    }

    public void setActualStart(Instant actualStart) {
        this.actualStart = actualStart;
    }

    public Instant getActualEnd() {
        return actualEnd;
    }

    public void setActualEnd(Instant actualEnd) {
        this.actualEnd = actualEnd;
    }

    public Integer getKmPlanned() {
        return kmPlanned;
    }

    public void setKmPlanned(Integer kmPlanned) {
        this.kmPlanned = kmPlanned;
    }

    public Integer getKmActual() {
        return kmActual;
    }

    public void setKmActual(Integer kmActual) {
        this.kmActual = kmActual;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
