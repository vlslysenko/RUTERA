package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Audit journal (SMDP Dziennik rewritten in a generic form). Append-only, written by the service layer.
 * {@code firma_id} is nullable (service superuser actions) and the table is not tenant-filtered, so that
 * the superuser can read across firmas; services that show it to firma users filter by firma explicitly.
 * {@code request_id} is the same value as the MDC in the log files.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    public enum Channel { UI, API, MCP, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firma_id")
    private Long firmaId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 16)
    private Channel channel;

    @Column(name = "module", nullable = false, length = 32)
    private String module;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "entity", length = 64)
    private String entity;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String details;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "ip", length = 45)
    private String ip;

    public Long getId() {
        return id;
    }

    public Long getFirmaId() {
        return firmaId;
    }

    public void setFirmaId(Long firmaId) {
        this.firmaId = firmaId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
