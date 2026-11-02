package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Token for the public API and MCP (paid "AI access" module). Issued only when {@code firma.ai_access} is true;
 * rights are the rights of the owning user. PERSONAL: created by the user, the token itself is shown once. OAUTH: the
 * connection of an OAuth client (claude.ai connector): {@code tokenHash} is the current access token, valid until
 * {@code accessExpiresAt}, {@code refreshHash} the current refresh token, valid until {@code expiresAt}.
 */
@Entity
@Table(name = "api_token")
public class ApiToken extends TenantEntity {

    public enum Kind {
        PERSONAL, OAUTH
    }

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind = Kind.PERSONAL;

    @Column(name = "oauth_client_id")
    private Long oauthClientId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** First characters of the token, shown in the UI to recognise it. */
    @Column(name = "token_prefix", nullable = false, length = 12)
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @Column(name = "refresh_hash", length = 128)
    private String refreshHash;

    /** Optional JSON array narrowing the token below the user's rights. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scopes")
    private String scopes;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public Long getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(Long oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    public String getRefreshHash() {
        return refreshHash;
    }

    public void setRefreshHash(String refreshHash) {
        this.refreshHash = refreshHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
