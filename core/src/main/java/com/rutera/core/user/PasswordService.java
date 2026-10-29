package com.rutera.core.user;

import com.rutera.common.domain.AppUser;
import com.rutera.core.security.RuteraSecurityProperties;
import com.rutera.core.web.ErrorCodes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Password rules shared by the own-password change and the admin reset: length between
 * {@code rutera.security.password-min-length} and {@link RuteraSecurityProperties#PASSWORD_MAX_LENGTH}.
 * Setting a password also clears a lockout, because the person who sets it has just proven who they are.
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder;
    private final RuteraSecurityProperties properties;

    public PasswordService(PasswordEncoder encoder, RuteraSecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** Adds a {@code validation.*} code for {@code field} to {@code errors} when the password breaks a rule. */
    public void check(String field, String password, Map<String, String> errors) {
        if (password == null || password.isBlank()) {
            errors.put(field, ErrorCodes.FIELD_REQUIRED);
        } else if (password.length() < properties.passwordMinLength()) {
            errors.put(field, ErrorCodes.FIELD_TOO_SHORT);
        } else if (password.length() > RuteraSecurityProperties.PASSWORD_MAX_LENGTH) {
            errors.put(field, ErrorCodes.FIELD_TOO_LONG);
        }
    }

    public boolean matches(String rawPassword, AppUser user) {
        return user.getPasswordHash() != null && encoder.matches(rawPassword, user.getPasswordHash());
    }

    /** Stores the new hash and lifts a lockout; the caller saves the entity and writes the audit entry. */
    public void apply(AppUser user, String newPassword) {
        user.setPasswordHash(encoder.encode(newPassword));
        user.setFailedLogins(0);
        user.setLockedUntil(null);
    }

    public int minLength() {
        return properties.passwordMinLength();
    }
}
