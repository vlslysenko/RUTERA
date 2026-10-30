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
 * Tachograph driver card. The DDD import finds the driver by {@link #getCardNumber()} (unique in the firma) and
 * sets {@link #getLastDownloadAt()}. For now a driver has at most one card, edited with the driver; the table
 * already allows a history of cards.
 */
@Entity
@Table(name = "driver_card")
public class DriverCard extends TenantEntity {

    public enum Generation {
        GEN1, GEN2
    }

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "card_number", nullable = false, length = 32)
    private String cardNumber;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "issuing_country", nullable = false, length = 2)
    private String issuingCountry;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "generation", length = 32)
    private Generation generation;

    @Column(name = "last_download_at")
    private Instant lastDownloadAt;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getIssuingCountry() {
        return issuingCountry;
    }

    public void setIssuingCountry(String issuingCountry) {
        this.issuingCountry = issuingCountry;
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

    public Generation getGeneration() {
        return generation;
    }

    public void setGeneration(Generation generation) {
        this.generation = generation;
    }

    public Instant getLastDownloadAt() {
        return lastDownloadAt;
    }

    public void setLastDownloadAt(Instant lastDownloadAt) {
        this.lastDownloadAt = lastDownloadAt;
    }
}
