package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/** One uploaded provider export with its counts, the mapping used and the first row errors (JSON). */
@Entity
@Table(name = "fuel_import")
public class FuelImport extends TenantEntity {

    public enum Status {
        DONE, FAILED
    }

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, length = 16)
    private FuelProvider provider;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;

    @Column(name = "rows_new", nullable = false)
    private int rowsNew;

    @Column(name = "rows_dup", nullable = false)
    private int rowsDup;

    @Column(name = "rows_error", nullable = false)
    private int rowsError;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapping")
    private String mapping;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors")
    private String errors;

    @Column(name = "imported_by")
    private Long importedBy;

    public FuelProvider getProvider() {
        return provider;
    }

    public void setProvider(FuelProvider provider) {
        this.provider = provider;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodFrom = periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodTo = periodTo;
    }

    public int getRowsTotal() {
        return rowsTotal;
    }

    public void setRowsTotal(int rowsTotal) {
        this.rowsTotal = rowsTotal;
    }

    public int getRowsNew() {
        return rowsNew;
    }

    public void setRowsNew(int rowsNew) {
        this.rowsNew = rowsNew;
    }

    public int getRowsDup() {
        return rowsDup;
    }

    public void setRowsDup(int rowsDup) {
        this.rowsDup = rowsDup;
    }

    public int getRowsError() {
        return rowsError;
    }

    public void setRowsError(int rowsError) {
        this.rowsError = rowsError;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMapping() {
        return mapping;
    }

    public void setMapping(String mapping) {
        this.mapping = mapping;
    }

    public String getErrors() {
        return errors;
    }

    public void setErrors(String errors) {
        this.errors = errors;
    }

    public Long getImportedBy() {
        return importedBy;
    }

    public void setImportedBy(Long importedBy) {
        this.importedBy = importedBy;
    }
}
