package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Units of a currency per 1 EUR on a day, from a source (ECB). Shared by all firmas. */
@Entity
@Table(name = "fx_rate")
public class FxRate extends BaseEntity {

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    public LocalDate getRateDate() {
        return rateDate;
    }

    public void setRateDate(LocalDate rateDate) {
        this.rateDate = rateDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
