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
 * A manual entry in a driver's working calendar. Days without one are computed from the tachograph data and the
 * absences; an entry here overrides that for its day.
 */
@Entity
@Table(name = "driver_day")
public class DriverDay extends TenantEntity {

    public enum Kind {
        WORK, REST, ABSENCE, HOLIDAY
    }

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "note", length = 500)
    private String note;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
