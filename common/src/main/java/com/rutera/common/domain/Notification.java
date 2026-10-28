package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** In-app notification for one user (bell in the top bar). */
@Entity
@Table(name = "notification")
public class Notification extends TenantEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** DOCUMENT_EXPIRING, VIOLATION, INVOICE_OVERDUE, IMPORT_FAILED, ... */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "link", length = 500)
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}
