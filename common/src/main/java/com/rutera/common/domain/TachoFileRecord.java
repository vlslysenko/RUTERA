package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An uploaded tachograph download (table {@code tacho_file}; named "record" to keep it apart from the parser's
 * {@code com.rutera.tacho.TachoFile}). The bytes live in {@link StoredFile}; the columns hold the summary of the
 * parsed file and the driver or vehicle it was matched to (docs/TACHO_IMPORT_API.md).
 */
@Entity
@Table(name = "tacho_file")
public class TachoFileRecord extends TenantEntity {

    public enum Kind {
        DRIVER_CARD, VEHICLE_UNIT
    }

    public enum Generation {
        GEN1, GEN2, GEN2V2
    }

    public enum Source {
        UPLOAD
    }

    /** How the driver or vehicle was found. */
    public enum Match {
        CARD_NUMBER, DRIVER_IDENTIFICATION, VIN, PLATE, MANUAL, NONE
    }

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "generation", nullable = false, length = 32)
    private Generation generation;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 32)
    private Source source = Source.UPLOAD;

    @Column(name = "card_number", length = 32)
    private String cardNumber;

    @Column(name = "card_issuing_country", length = 2)
    private String cardIssuingCountry;

    @Column(name = "card_valid_to")
    private LocalDate cardValidTo;

    @Column(name = "vin", length = 17)
    private String vin;

    @Column(name = "plate", length = 20)
    private String plate;

    @Column(name = "plate_country", length = 2)
    private String plateCountry;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @Column(name = "warnings_count", nullable = false)
    private int warningsCount;

    /** JSON array of the parser's warnings (technical text, no personal data). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings")
    private String warnings;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "match_kind", nullable = false, length = 32)
    private Match match = Match.NONE;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public Generation getGeneration() {
        return generation;
    }

    public void setGeneration(Generation generation) {
        this.generation = generation;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getCardIssuingCountry() {
        return cardIssuingCountry;
    }

    public void setCardIssuingCountry(String cardIssuingCountry) {
        this.cardIssuingCountry = cardIssuingCountry;
    }

    public LocalDate getCardValidTo() {
        return cardValidTo;
    }

    public void setCardValidTo(LocalDate cardValidTo) {
        this.cardValidTo = cardValidTo;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate;
    }

    public String getPlateCountry() {
        return plateCountry;
    }

    public void setPlateCountry(String plateCountry) {
        this.plateCountry = plateCountry;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodFrom = periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodTo = periodTo;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }

    public void setDownloadedAt(Instant downloadedAt) {
        this.downloadedAt = downloadedAt;
    }

    public int getWarningsCount() {
        return warningsCount;
    }

    public void setWarningsCount(int warningsCount) {
        this.warningsCount = warningsCount;
    }

    public String getWarnings() {
        return warnings;
    }

    public void setWarnings(String warnings) {
        this.warnings = warnings;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Match getMatch() {
        return match;
    }

    public void setMatch(Match match) {
        this.match = match;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
