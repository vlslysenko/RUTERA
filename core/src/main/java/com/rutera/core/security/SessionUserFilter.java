package com.rutera.core.security;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Sits in the security filter chain right after the security context has been restored from the session.
 * For a signed-in user it re-checks the account in the database (deleted, disabled, locked, firma inactive)
 * and then scopes the request: {@link RequestContext} gets the firma and user (root scope for the superuser)
 * and the MDC gets {@code firmaId} / {@code userId}. A session whose user is no longer usable is invalidated
 * and the request continues anonymously, so protected endpoints answer 401.
 * <p>
 * Not a Spring bean on purpose: {@code SecurityConfig} instantiates it, otherwise Boot would also register it
 * as a plain servlet filter outside the chain.
 */
public class SessionUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionUserFilter.class);

    private final SessionUserService sessionUsers;

    public SessionUserFilter(SessionUserService sessionUsers) {
        this.sessionUsers = sessionUsers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            Optional<AppUser> valid = sessionUsers.validate(principal, Instant.now());
            if (valid.isPresent()) {
                scope(principal);
            } else {
                log.info("Session of user {} dropped: account no longer usable", principal.id());
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clearTenant();
            MDC.remove(RequestIdFilter.MDC_FIRMA_ID);
            MDC.remove(RequestIdFilter.MDC_USER_ID);
        }
    }

    /** Also used by the login flow once the user has been authenticated within the same request. */
    public static void scope(AuthenticatedUser principal) {
        if (principal.role() == Role.SUPERUSER) {
            RequestContext.setRoot(principal.id());
        } else {
            RequestContext.setFirma(principal.firmaId(), principal.id());
        }
        MDC.put(RequestIdFilter.MDC_USER_ID, String.valueOf(principal.id()));
        if (principal.firmaId() != null) {
            MDC.put(RequestIdFilter.MDC_FIRMA_ID, String.valueOf(principal.firmaId()));
        }
    }
}
