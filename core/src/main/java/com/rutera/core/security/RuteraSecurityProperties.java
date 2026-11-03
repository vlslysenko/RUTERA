package com.rutera.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login and session protection settings ({@code rutera.security.*} in application.properties).
 *
 * @param lockoutMaxAttempts    failed attempts in a row after which the account is locked (SMDP: 5)
 * @param lockoutDuration       how long the lock lasts; the counter restarts afterwards
 * @param bcryptStrength        BCrypt cost factor for new password hashes
 * @param passwordMinLength     minimum length of a new password (own change, admin reset, new user)
 * @param ipMaxFailures         failed sign-ins from one client address within {@code ipWindow} after which that
 *                              address gets 429 until the window ends (any login, so scans of many logins stop)
 * @param ipWindow              the window of {@code ipMaxFailures}
 * @param sessionMaxAge         absolute lifetime of a session from the sign-in, however active it is
 * @param contentSecurityPolicy the Content-Security-Policy of the web application
 */
@ConfigurationProperties(prefix = "rutera.security")
public record RuteraSecurityProperties(int lockoutMaxAttempts, Duration lockoutDuration, int bcryptStrength,
                                       int passwordMinLength, int ipMaxFailures, Duration ipWindow,
                                       Duration sessionMaxAge, String contentSecurityPolicy) {

    /** Upper bound of a password: BCrypt ignores everything after 72 bytes anyway. */
    public static final int PASSWORD_MAX_LENGTH = 72;

    /**
     * Same origin for everything, map tiles from tile.openstreetmap.org, inline styles (Mantine), the service worker
     * and the manifest from the origin, never framed. No form-action: the OAuth consent redirects to the client.
     */
    public static final String DEFAULT_CSP = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://tile.openstreetmap.org; "
            + "font-src 'self' data:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; "
            + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

    public RuteraSecurityProperties {
        if (lockoutMaxAttempts <= 0) {
            lockoutMaxAttempts = 5;
        }
        if (lockoutDuration == null || lockoutDuration.isNegative() || lockoutDuration.isZero()) {
            lockoutDuration = Duration.ofMinutes(15);
        }
        if (bcryptStrength < 4 || bcryptStrength > 31) {
            bcryptStrength = 12;
        }
        if (passwordMinLength <= 0 || passwordMinLength > PASSWORD_MAX_LENGTH) {
            passwordMinLength = 10;
        }
        if (ipMaxFailures <= 0) {
            ipMaxFailures = 20;
        }
        if (ipWindow == null || ipWindow.isNegative() || ipWindow.isZero()) {
            ipWindow = Duration.ofMinutes(15);
        }
        if (sessionMaxAge == null || sessionMaxAge.isNegative() || sessionMaxAge.isZero()) {
            sessionMaxAge = Duration.ofHours(12);
        }
        if (contentSecurityPolicy == null || contentSecurityPolicy.isBlank()) {
            contentSecurityPolicy = DEFAULT_CSP;
        }
    }
}
