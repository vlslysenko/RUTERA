package com.rutera.core.security;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Role;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Builds the session principal from a database user and re-validates it on every request.
 */
@Service
public class SessionUserService {

    /** Why an account may not be used right now; maps 1:1 to the {@code auth.login.*} codes. */
    public enum Rejection { DISABLED, LOCKED, FIRMA_INACTIVE }

    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final PrivilegeService privileges;

    public SessionUserService(AppUserRepository users, FirmaRepository firmas, PrivilegeService privileges) {
        this.users = users;
        this.firmas = firmas;
        this.privileges = privileges;
    }

    /** Checks whether the account (and its firma) may be used at {@code now}; empty means it may. */
    public Optional<Rejection> rejectionOf(AppUser user, Instant now) {
        if (user.isDeleted() || !user.isActive()) {
            return Optional.of(Rejection.DISABLED);
        }
        if (user.isLockedAt(now)) {
            return Optional.of(Rejection.LOCKED);
        }
        if (user.getRole() != Role.SUPERUSER) {
            if (user.getFirmaId() == null) {
                return Optional.of(Rejection.FIRMA_INACTIVE);
            }
            Optional<Firma> firma = firmas.findById(user.getFirmaId());
            if (firma.isEmpty() || !firma.get().isActive()
                    || (firma.get().getExpiresAt() != null && firma.get().getExpiresAt().isBefore(now))) {
                return Optional.of(Rejection.FIRMA_INACTIVE);
            }
        }
        return Optional.empty();
    }

    /** Principal snapshot with the effective privileges (see {@link PrivilegeService}). */
    public AuthenticatedUser principalOf(AppUser user) {
        return new AuthenticatedUser(user.getId(), user.getLogin(), user.getFirmaId(), user.getRole(),
                user.getLang(), privileges.effectivePrivileges(user));
    }

    public Authentication authenticationOf(AuthenticatedUser principal) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    /**
     * Per-request check of a session principal: the user must still exist and be usable. Returns the
     * database row, or empty when the session has to be dropped.
     */
    public Optional<AppUser> validate(AuthenticatedUser principal, Instant now) {
        return users.findById(principal.id())
                .filter(user -> rejectionOf(user, now).isEmpty());
    }
}
