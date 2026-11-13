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
 * A driver's written explanation of a tachograph infringement. The infringement's data is a copy (violations are
 * recomputed after every import); {@link #getViolationId()} is cleared when the violation disappears.
 */
@Entity
@Table(name = "driver_explanation")
public class DriverExplanation extends TenantEntity {

    public enum Status {
        PENDING, SIGNED, CANCELLED
    }

    public enum Source {
        AUTO, MANUAL
    }

    /** Art. 12 of Regulation 561/2006 first (stopping place, safety, return for the weekly rest), then everyday ones. */
    public enum Reason {
        SAFE_STOPPING_PLACE, SAFETY, RETURN_FOR_WEEKLY_REST, TRAFFIC_JAM, NO_PARKING, ACCIDENT, ROAD_CLOSURE, BREAKDOWN,
        WEATHER, LOADING_DELAY, OTHER
    }

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "violation_id")
    private Long violationId;

    /** A {@link DriverViolation.Rule} name. */
    @Column(name = "rule_code", length = 32)
    private String ruleCode;

    @Column(name = "vehicle_plate", length = 16)
    private String vehiclePlate;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "actual_min")
    private Integer actualMin;

    @Column(name = "limit_min")
    private Integer limitMin;

    @Column(name = "exceeded_min")
    private Integer exceededMin;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason", length = 32)
    private Reason reason;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    @Column(name = "place", length = 100)
    private String place;

    @Column(name = "doc_lang", length = 5)
    private String docLang;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_by")
    private Long signedBy;

    @Column(name = "signature_file_id")
    private Long signatureFileId;

    @Column(name = "pdf_file_id")
    private Long pdfFileId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getViolationId() {
        return violationId;
    }

    public void setViolationId(Long violationId) {
        this.violationId = violationId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getVehiclePlate() {
        return vehiclePlate;
    }

    public void setVehiclePlate(String vehiclePlate) {
        this.vehiclePlate = vehiclePlate;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Integer getActualMin() {
        return actualMin;
    }

    public void setActualMin(Integer actualMin) {
        this.actualMin = actualMin;
    }

    public Integer getLimitMin() {
        return limitMin;
    }

    public void setLimitMin(Integer limitMin) {
        this.limitMin = limitMin;
    }

    public Integer getExceededMin() {
        return exceededMin;
    }

    public void setExceededMin(Integer exceededMin) {
        this.exceededMin = exceededMin;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Reason getReason() {
        return reason;
    }

    public void setReason(Reason reason) {
        this.reason = reason;
    }

    public String getReasonText() {
        return reasonText;
    }

    public void setReasonText(String reasonText) {
        this.reasonText = reasonText;
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = place;
    }

    public String getDocLang() {
        return docLang;
    }

    public void setDocLang(String docLang) {
        this.docLang = docLang;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public Long getSignedBy() {
        return signedBy;
    }

    public void setSignedBy(Long signedBy) {
        this.signedBy = signedBy;
    }

    public Long getSignatureFileId() {
        return signatureFileId;
    }

    public void setSignatureFileId(Long signatureFileId) {
        this.signatureFileId = signatureFileId;
    }

    public Long getPdfFileId() {
        return pdfFileId;
    }

    public void setPdfFileId(Long pdfFileId) {
        this.pdfFileId = pdfFileId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Long cancelledBy) {
        this.cancelledBy = cancelledBy;
    }
}
