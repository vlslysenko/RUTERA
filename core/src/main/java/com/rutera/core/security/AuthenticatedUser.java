package com.rutera.core.security;

import com.rutera.common.security.Role;
import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * The signed-in user as kept in the HTTP session (principal of the {@code Authentication}).
 * <p>
 * A snapshot taken at login and refreshed by {@code GET /app/auth/me}: id, login, firma, role, language and
 * the effective privilege set. {@code firmaId} is {@code null} only for the service superuser. Every request
 * additionally re-checks the account state in the database (see {@link SessionUserFilter}), so a disabled user
 * loses access at once even though this snapshot is older.
 */
public record AuthenticatedUser(
        Long id,
        String login,
        Long firmaId,
        Role role,
        String lang,
        Set<String> privileges) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public AuthenticatedUser {
        privileges = privileges == null ? Set.of() : Set.copyOf(privileges);
    }

    @Override
    public String getName() {
        return login;
    }

    public boolean isSuperuser() {
        return role != null && role.isSuperuser();
    }

    /** True when the user is the superuser or holds the privilege key. */
    public boolean hasPrivilege(String key) {
        return isSuperuser() || privileges.contains(key);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[" + id + " " + login + " " + role + " firma=" + firmaId + "]";
    }
}
