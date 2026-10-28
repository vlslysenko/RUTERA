package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tenant company (SMDP Firma, trimmed to the common fields). In standalone mode there is exactly one row.
 * Not a {@link TenantEntity}: it is the tenant itself.
 */
@Entity
@Table(name = "firma")
public class Firma extends BaseEntity {

    public static final long STANDALONE_FIRMA_ID = 1L;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "vat_id", length = 32)
    private String vatId;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "lang", nullable = false, length = 5)
    private String lang = "en";

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency = "EUR";

    /** Subscription plan code (replaces SMDP TypSystemu); license checks in the service layer read it. */
    @Column(name = "plan", nullable = false, length = 32)
    private String plan = "STANDARD";

    @Column(name = "vehicle_limit", nullable = false)
    private int vehicleLimit = 10;

    /** Paid "AI access" module (public API + MCP) enabled for this firma. */
    @Column(name = "ai_access", nullable = false)
    private boolean aiAccess;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getVatId() {
        return vatId;
    }

    public void setVatId(String vatId) {
        this.vatId = vatId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public int getVehicleLimit() {
        return vehicleLimit;
    }

    public void setVehicleLimit(int vehicleLimit) {
        this.vehicleLimit = vehicleLimit;
    }

    public boolean isAiAccess() {
        return aiAccess;
    }

    public void setAiAccess(boolean aiAccess) {
        this.aiAccess = aiAccess;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isTest() {
        return test;
    }

    public void setTest(boolean test) {
        this.test = test;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
