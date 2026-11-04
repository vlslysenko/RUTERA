package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A browser or phone subscribed to Web Push for one user: the push service endpoint of the device and the device
 * keys (base64url) that encrypt the payload (RFC 8291).
 */
@Entity
@Table(name = "push_subscription")
public class PushSubscription extends TenantEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "endpoint", nullable = false, length = 1000)
    private String endpoint;

    /** SHA-256 hex of the endpoint, unique. */
    @Column(name = "endpoint_hash", nullable = false, length = 64)
    private String endpointHash;

    @Column(name = "p256dh", nullable = false, length = 128)
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 64)
    private String auth;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    /** Failed deliveries in a row. */
    @Column(name = "failures", nullable = false)
    private int failures;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getEndpointHash() {
        return endpointHash;
    }

    public void setEndpointHash(String endpointHash) {
        this.endpointHash = endpointHash;
    }

    public String getP256dh() {
        return p256dh;
    }

    public void setP256dh(String p256dh) {
        this.p256dh = p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public int getFailures() {
        return failures;
    }

    public void setFailures(int failures) {
        this.failures = failures;
    }
}
