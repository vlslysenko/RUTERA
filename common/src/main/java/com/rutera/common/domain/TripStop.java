package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** A stop of a trip, in the order of {@link #getSeq()}; loading and unloading stops may name their order. */
@Entity
@Table(name = "trip_stop")
public class TripStop extends TenantEntity {

    public enum Kind {
        LOADING, UNLOADING, BORDER, REST, FUEL, CUSTOMS, OTHER
    }

    public enum Status {
        PLANNED, DONE, SKIPPED
    }

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "planned_at")
    private Instant plannedAt;

    @Column(name = "actual_at")
    private Instant actualAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.PLANNED;

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
    }

    public Instant getPlannedAt() {
        return plannedAt;
    }

    public void setPlannedAt(Instant plannedAt) {
        this.plannedAt = plannedAt;
    }

    public Instant getActualAt() {
        return actualAt;
    }

    public void setActualAt(Instant actualAt) {
        this.actualAt = actualAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
