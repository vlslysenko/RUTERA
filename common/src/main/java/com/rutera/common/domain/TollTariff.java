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

/** A toll rate per km in a country for the trip calculator; null conditions match every vehicle. */
@Entity
@Table(name = "toll_tariff")
public class TollTariff extends TenantEntity {

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "euro_class", length = 16)
    private Vehicle.EuroClass euroClass;

    @Column(name = "axles_min")
    private Integer axlesMin;

    @Column(name = "weight_min_kg")
    private Integer weightMinKg;

    @Column(name = "rate_per_km", nullable = false, precision = 10, scale = 4)
    private BigDecimal ratePerKm;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "notes", length = 500)
    private String notes;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Vehicle.EuroClass getEuroClass() {
        return euroClass;
    }

    public void setEuroClass(Vehicle.EuroClass euroClass) {
        this.euroClass = euroClass;
    }

    public Integer getAxlesMin() {
        return axlesMin;
    }

    public void setAxlesMin(Integer axlesMin) {
        this.axlesMin = axlesMin;
    }

    public Integer getWeightMinKg() {
        return weightMinKg;
    }

    public void setWeightMinKg(Integer weightMinKg) {
        this.weightMinKg = weightMinKg;
    }

    public BigDecimal getRatePerKm() {
        return ratePerKm;
    }

    public void setRatePerKm(BigDecimal ratePerKm) {
        this.ratePerKm = ratePerKm;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
