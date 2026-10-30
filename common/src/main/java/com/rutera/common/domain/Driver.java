package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * Driver as an employee (docs/DB_SCHEMA.md, group 3). A driver may exist without a login; {@link #getUserId()} is
 * set when they have one (driver cabinet). The usual vehicle is {@code vehicle.default_driver_id}, not a column here.
 */
@Entity
@Table(name = "driver")
public class Driver extends TenantEntity {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "employment_from")
    private LocalDate employmentFrom;

    @Column(name = "employment_to")
    private LocalDate employmentTo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.ACTIVE;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    /** "First Last", the way the screens show a driver. */
    public String getFullName() {
        return (firstName + " " + lastName).trim();
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getEmploymentFrom() {
        return employmentFrom;
    }

    public void setEmploymentFrom(LocalDate employmentFrom) {
        this.employmentFrom = employmentFrom;
    }

    public LocalDate getEmploymentTo() {
        return employmentTo;
    }

    public void setEmploymentTo(LocalDate employmentTo) {
        this.employmentTo = employmentTo;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
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
