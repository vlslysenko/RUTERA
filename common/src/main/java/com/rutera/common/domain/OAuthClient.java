package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * OAuth client registered by dynamic client registration (RFC 7591), e.g. the claude.ai connector. Global, not tied
 * to a firma: the firma comes with the user who authorizes the client.
 */
@Entity
@Table(name = "oauth_client")
public class OAuthClient extends BaseEntity {

    /** How the client authenticates at the token endpoint (RFC 7591 token_endpoint_auth_method). */
    public static final String AUTH_NONE = "none";
    public static final String AUTH_SECRET_POST = "client_secret_post";
    public static final String AUTH_SECRET_BASIC = "client_secret_basic";

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 100)
    private String clientName;

    /** JSON array of the exact redirect URIs. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redirect_uris", nullable = false)
    private String redirectUris;

    @Column(name = "auth_method", nullable = false, length = 32)
    private String authMethod;

    /** SHA-256 hex of the client secret; null for public clients. */
    @Column(name = "client_secret_hash", length = 128)
    private String clientSecretHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getRedirectUris() {
        return redirectUris;
    }

    public void setRedirectUris(String redirectUris) {
        this.redirectUris = redirectUris;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public void setClientSecretHash(String clientSecretHash) {
        this.clientSecretHash = clientSecretHash;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
