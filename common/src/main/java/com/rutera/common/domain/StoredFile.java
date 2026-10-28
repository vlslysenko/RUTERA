package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Any stored file: CMR, DDD, fuel export, invoice PDF, scan, receipt. Content lives on disk or in S3. */
@Entity
@Table(name = "stored_file")
public class StoredFile extends TenantEntity {

    public enum Storage { DISK, S3 }

    /** Business kind: CMR, DDD, FUEL_IMPORT, INVOICE, DOCUMENT, RECEIPT, OTHER. */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "storage", nullable = false, length = 16)
    private Storage storage;

    /** Path on disk or bucket key, always prefixed with the firma id. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "mime", nullable = false, length = 127)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
