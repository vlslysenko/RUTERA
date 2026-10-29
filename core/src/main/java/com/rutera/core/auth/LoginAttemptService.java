package com.rutera.core.auth;

import com.rutera.common.domain.AppUser;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.RuteraSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bookkeeping of sign-in attempts in their own transactions, so that a rejected login still leaves its trace
 * ({@code failed_logins}, {@code locked_until}, audit) while the login request itself answers 401.
 * Lockout as in SMDP: after {@code lockoutMaxAttempts} failures in a row the account is locked for
 * {@code lockoutDuration}; a successful sign-in resets the counter.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final AppUserRepository users;
    private final AuditService audit;
    private final RuteraSecurityProperties properties;

    public LoginAttemptService(AppUserRepository users, AuditService audit, RuteraSecurityProperties properties) {
        this.users = users;
        this.audit = audit;
        this.properties = properties;
    }

    /** Wrong password for an existing account: counts the failure and locks the account when the limit is hit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void wrongPassword(AppUser user, Instant now) {
        int failures = user.getFailedLogins() + 1;
        boolean lock = failures >= properties.lockoutMaxAttempts();
        if (lock) {
            Instant until = now.plus(properties.lockoutDuration());
            users.lock(user.getId(), until);
            log.warn("User {} locked until {} after {} failed sign-in attempts", user.getId(), until, failures);
        } else {
            users.incrementFailedLogins(user.getId());
        }
        audit.record(AuditEvent.of(AuthService.MODULE, "login_failed", "Sign-in failed: wrong password")
                        .entity("app_user", user.getId())
                        .detail("reason", "password")
                        .detail("failures", failures)
                        .detail("locked", lock),
                user.getFirmaId(), user.getId());
    }

    /** Login refused for another reason (locked, disabled, firma inactive): audit only. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(AppUser user, String reason) {
        audit.record(AuditEvent.of(AuthService.MODULE, "login_failed", "Sign-in refused: " + reason)
                        .entity("app_user", user.getId())
                        .detail("reason", reason),
                user.getFirmaId(), user.getId());
    }

    /** Unknown login: audited without a user, so that scans of logins remain visible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unknownLogin(String login) {
        audit.record(AuditEvent.of(AuthService.MODULE, "login_failed", "Sign-in failed: unknown login")
                        .detail("reason", "unknown")
                        .detail("login", login),
                null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(AppUser user) {
        if (user.getFailedLogins() > 0 || user.getLockedUntil() != null) {
            users.resetFailedLogins(user.getId());
        }
        audit.record(AuditEvent.of(AuthService.MODULE, "login", "Signed in").entity("app_user", user.getId()),
                user.getFirmaId(), user.getId());
    }
}
