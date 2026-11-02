package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A photo or document of a trip (CMR photo, delivery note, receipt), uploaded by the driver or the office. */
@Entity
@Table(name = "trip_file")
public class TripFile extends TenantEntity {

    public enum Kind {
        CMR_PHOTO, DELIVERY_NOTE, RECEIPT, OTHER
    }

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "stop_id")
    private Long stopId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Long getStopId() {
        return stopId;
    }

    public void setStopId(Long stopId) {
        this.stopId = stopId;
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

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }
}
