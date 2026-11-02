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
 * Customer order (table {@code transport_order}, docs/DB_SCHEMA.md group 5). It names its loading and unloading
 * place itself; once a {@link Trip} carries it ({@link #getTripId()}), the status follows the trip.
 */
@Entity
@Table(name = "transport_order")
public class TransportOrder extends TenantEntity {

    public enum Status {
        NEW, PLANNED, IN_PROGRESS, DONE, INVOICED, CANCELLED
    }

    public enum Source {
        MANUAL
    }

    @Column(name = "number", nullable = false, length = 32)
    private String number;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "contractor_id", nullable = false)
    private Long contractorId;

    /** The customer's own order number. */
    @Column(name = "customer_ref", length = 64)
    private String customerRef;

    @Column(name = "price", precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "cargo", length = 500)
    private String cargo;

    @Column(name = "weight_kg")
    private Integer weightKg;

    @Column(name = "loading_place", nullable = false, length = 200)
    private String loadingPlace;

    @Column(name = "loading_country", nullable = false, length = 2)
    private String loadingCountry;

    @Column(name = "loading_date")
    private LocalDate loadingDate;

    @Column(name = "unloading_place", nullable = false, length = 200)
    private String unloadingPlace;

    @Column(name = "unloading_country", nullable = false, length = 2)
    private String unloadingCountry;

    @Column(name = "unloading_date")
    private LocalDate unloadingDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.NEW;

    @Column(name = "trip_id")
    private Long tripId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 32)
    private Source source = Source.MANUAL;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "notes", length = 2000)
    private String notes;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public Long getContractorId() {
        return contractorId;
    }

    public void setContractorId(Long contractorId) {
        this.contractorId = contractorId;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCargo() {
        return cargo;
    }

    public void setCargo(String cargo) {
        this.cargo = cargo;
    }

    public Integer getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(Integer weightKg) {
        this.weightKg = weightKg;
    }

    public String getLoadingPlace() {
        return loadingPlace;
    }

    public void setLoadingPlace(String loadingPlace) {
        this.loadingPlace = loadingPlace;
    }

    public String getLoadingCountry() {
        return loadingCountry;
    }

    public void setLoadingCountry(String loadingCountry) {
        this.loadingCountry = loadingCountry;
    }

    public LocalDate getLoadingDate() {
        return loadingDate;
    }

    public void setLoadingDate(LocalDate loadingDate) {
        this.loadingDate = loadingDate;
    }

    public String getUnloadingPlace() {
        return unloadingPlace;
    }

    public void setUnloadingPlace(String unloadingPlace) {
        this.unloadingPlace = unloadingPlace;
    }

    public String getUnloadingCountry() {
        return unloadingCountry;
    }

    public void setUnloadingCountry(String unloadingCountry) {
        this.unloadingCountry = unloadingCountry;
    }

    public LocalDate getUnloadingDate() {
        return unloadingDate;
    }

    public void setUnloadingDate(LocalDate unloadingDate) {
        this.unloadingDate = unloadingDate;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
