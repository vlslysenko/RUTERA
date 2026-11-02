package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * A line item of an invoice. {@link #getVatCode()} RATE uses {@link #getVatRate()} (percent); ZW (exempt), NP (not
 * taxable here, typical for international transport) and OO (reverse charge) carry no VAT.
 */
@Entity
@Table(name = "invoice_line")
public class InvoiceLine extends TenantEntity {

    public enum VatCode {
        RATE, ZW, NP, OO
    }

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "vat_code", nullable = false, length = 8)
    private VatCode vatCode = VatCode.RATE;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "net", nullable = false, precision = 14, scale = 2)
    private BigDecimal net;

    @Column(name = "vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal vat;

    @Column(name = "gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal gross;

    public Long getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public VatCode getVatCode() {
        return vatCode;
    }

    public void setVatCode(VatCode vatCode) {
        this.vatCode = vatCode;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }

    public BigDecimal getVat() {
        return vat;
    }

    public void setVat(BigDecimal vat) {
        this.vat = vat;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public void setGross(BigDecimal gross) {
        this.gross = gross;
    }
}
