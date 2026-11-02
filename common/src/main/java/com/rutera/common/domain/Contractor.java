package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Customer, subcontracted carrier or supplier (docs/DB_SCHEMA.md, group 5). The VAT id is unique among the
 * non-deleted contractors of the firma (column {@code vat_key}, maintained by MySQL).
 */
@Entity
@Table(name = "contractor")
public class Contractor extends TenantEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Upper-case, without spaces and hyphens. */
    @Column(name = "vat_id", length = 32)
    private String vatId;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    /** JSON array of e-mail addresses for invoices and payment reminders. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "invoice_emails")
    private String invoiceEmails;

    @Column(name = "payment_days")
    private Integer paymentDays;

    @Column(name = "lang", length = 5)
    private String lang;

    @Column(name = "is_customer", nullable = false)
    private boolean customer = true;

    @Column(name = "is_carrier", nullable = false)
    private boolean carrier;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVatId() {
        return vatId;
    }

    public void setVatId(String vatId) {
        this.vatId = vatId;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getInvoiceEmails() {
        return invoiceEmails;
    }

    public void setInvoiceEmails(String invoiceEmails) {
        this.invoiceEmails = invoiceEmails;
    }

    public Integer getPaymentDays() {
        return paymentDays;
    }

    public void setPaymentDays(Integer paymentDays) {
        this.paymentDays = paymentDays;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public boolean isCustomer() {
        return customer;
    }

    public void setCustomer(boolean customer) {
        this.customer = customer;
    }

    public boolean isCarrier() {
        return carrier;
    }

    public void setCarrier(boolean carrier) {
        this.carrier = carrier;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
