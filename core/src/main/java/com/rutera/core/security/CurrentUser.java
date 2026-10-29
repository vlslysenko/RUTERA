package com.rutera.core.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Static access to the signed-in user of the current thread (Spring Security context). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthenticatedUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The signed-in user, or a 401 (handled by the web layer) when there is none. */
    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Not signed in"));
    }
}
