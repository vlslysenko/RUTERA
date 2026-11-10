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

/**
 * A fuel price in a country on a day: net per litre (kg for LNG and CNG), without VAT. Shared by all firmas; kept by
 * the operator or a price provider.
 */
@Entity
@Table(name = "fuel_price")
public class FuelPrice extends BaseEntity {

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "product", nullable = false, length = 16)
    private FuelTransaction.Product product;

    @Column(name = "price_net", nullable = false, precision = 10, scale = 4)
    private BigDecimal priceNet;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_on", nullable = false)
    private LocalDate validOn;

    @Column(name = "source", length = 200)
    private String source;

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

    public BigDecimal getPriceNet() {
        return priceNet;
    }

    public void setPriceNet(BigDecimal priceNet) {
        this.priceNet = priceNet;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getValidOn() {
        return validOn;
    }

    public void setValidOn(LocalDate validOn) {
        this.validOn = validOn;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
