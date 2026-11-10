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
import java.time.LocalDate;

/**
 * A reference fuel price in a country on a day ({@code fuel_price_ref}), per litre (kg for LNG/CNG) in the country's
 * currency: gross with VAT, net without VAT. Shared by all firmas; written by price providers or the operator.
 */
@Entity
@Table(name = "fuel_price_ref")
public class FuelPrice extends BaseEntity {

    /** AVERAGE: national average; CAP: official maximum price; STATION_MEDIAN: median of station prices. */
    public enum Kind {
        AVERAGE, CAP, STATION_MEDIAN
    }

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "product", nullable = false, length = 16)
    private FuelTransaction.Product product;

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "gross", precision = 12, scale = 4)
    private BigDecimal gross;

    @Column(name = "net", nullable = false, precision = 12, scale = 4)
    private BigDecimal net;

    /** Percent, e.g. 23.00. */
    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "excise_per_1000l", precision = 14, scale = 2)
    private BigDecimal excisePer1000l;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public FuelTransaction.Product getProduct() {
        return product;
    }

    public void setProduct(FuelTransaction.Product product) {
        this.product = product;
    }

    public LocalDate getPriceDate() {
        return priceDate;
    }

    public void setPriceDate(LocalDate priceDate) {
        this.priceDate = priceDate;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public void setGross(BigDecimal gross) {
        this.gross = gross;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public BigDecimal getExcisePer1000l() {
        return excisePer1000l;
    }

    public void setExcisePer1000l(BigDecimal excisePer1000l) {
        this.excisePer1000l = excisePer1000l;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
