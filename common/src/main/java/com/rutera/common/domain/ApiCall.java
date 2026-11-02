package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One call of the public API made with a personal token (audit; {@code createdAt} is the time of the call). */
@Entity
@Table(name = "api_call")
public class ApiCall extends TenantEntity {

    @Column(name = "token_id", nullable = false)
    private Long tokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "status", nullable = false)
    private int status;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "ip", length = 45)
    private String ip;

    public Long getTokenId() {
        return tokenId;
    }

    public void setTokenId(Long tokenId) {
        this.tokenId = tokenId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
