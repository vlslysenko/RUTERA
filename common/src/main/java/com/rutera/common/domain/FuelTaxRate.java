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

/** The VAT rate and excise of a fuel in a country for a period ({@code fuel_tax_rate}). Shared by all firmas. */
@Entity
@Table(name = "fuel_tax_rate")
public class FuelTaxRate extends BaseEntity {

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "product", nullable = false, length = 16)
    private FuelTransaction.Product product;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    /** Per 1000 l in {@link #getCurrency()}. */
    @Column(name = "excise_per_1000l", precision = 14, scale = 2)
    private BigDecimal excisePer1000l;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "note", length = 300)
    private String note;

    public String getCountry() {
        return country;
    }

    public FuelTransaction.Product getProduct() {
        return product;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public BigDecimal getExcisePer1000l() {
        return excisePer1000l;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public String getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }
}
