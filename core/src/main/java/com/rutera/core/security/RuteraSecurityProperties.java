package com.rutera.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login protection settings ({@code rutera.security.*} in application.properties).
 *
 * @param lockoutMaxAttempts failed attempts in a row after which the account is locked (SMDP: 5)
 * @param lockoutDuration    how long the lock lasts; the counter restarts afterwards
 * @param bcryptStrength     BCrypt cost factor for new password hashes
 * @param passwordMinLength  minimum length of a new password (own change, admin reset, new user)
 */
@ConfigurationProperties(prefix = "rutera.security")
public record RuteraSecurityProperties(int lockoutMaxAttempts, Duration lockoutDuration, int bcryptStrength,
                                       int passwordMinLength) {

    /** Upper bound of a password: BCrypt ignores everything after 72 bytes anyway. */
    public static final int PASSWORD_MAX_LENGTH = 72;

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
    }
}
