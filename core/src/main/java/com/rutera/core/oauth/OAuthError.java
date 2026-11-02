package com.rutera.core.oauth;

import org.springframework.http.HttpStatus;

/**
 * Error of the token, registration or revocation endpoint, answered as {@code {error, error_description}}
 * (RFC 6749 section 5.2, RFC 7591 section 3.2.2).
 */
public class OAuthError extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public OAuthError(HttpStatus status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
    }

    static OAuthError invalidRequest(String description) {
        return new OAuthError(HttpStatus.BAD_REQUEST, "invalid_request", description);
    }

    static OAuthError invalidGrant(String description) {
        return new OAuthError(HttpStatus.BAD_REQUEST, "invalid_grant", description);
    }

    static OAuthError invalidClient(String description) {
        return new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_client", description);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
