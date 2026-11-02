package com.rutera.core.api;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.ApiCall;
import com.rutera.common.domain.ApiToken;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.ApiCallRepository;
import com.rutera.common.repository.ApiTokenRepository;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Role;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.JsonSecurityErrors;
import com.rutera.core.security.RequestIdFilter;
import com.rutera.core.security.SessionUserFilter;
import com.rutera.core.security.SessionUserService;
import com.rutera.core.web.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Bearer authentication of the public API ({@code /api/**}, stateless): {@code Authorization: Bearer rtr_...}. The
 * token must exist, not be revoked or expired, its user must still be usable and the firma must have the AI access
 * add-on; then the request runs as that user (current privileges) in the firma's scope, like a session request.
 * Every call with a known token is written to {@code api_call}; the token's {@code lastUsedAt} moves at most once a
 * minute. Not a Spring bean: {@code SecurityConfig} creates it for the API filter chain only.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
    private static final String BEARER = "Bearer ";

    private final ApiTokenRepository tokens;
    private final ApiCallRepository calls;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final SessionUserService sessionUsers;
    private final ApiRateLimiter limiter;
    private final JsonSecurityErrors errors;
    private final TransactionTemplate tx;

    public ApiTokenFilter(ApiTokenRepository tokens, ApiCallRepository calls, AppUserRepository users,
                          FirmaRepository firmas, SessionUserService sessionUsers, ApiRateLimiter limiter,
                          JsonSecurityErrors errors, PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.calls = calls;
        this.users = users;
        this.firmas = firmas;
        this.sessionUsers = sessionUsers;
        this.limiter = limiter;
        this.errors = errors;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private record Resolved(ApiToken token, AppUser user, Firma firma) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        long started = System.nanoTime();
        Instant now = Instant.now();
        Resolved r = resolve(header.substring(BEARER.length()).strip(), now);
        if (r == null) {
            errors.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED);
            return;
        }
        try {
            if (!r.firma().isAiAccess()) {
                errors.write(request, response, HttpStatus.FORBIDDEN, ErrorCodes.API_AI_ACCESS_REQUIRED);
                return;
            }
            long wait = limiter.acquire(r.token().getId(), System.currentTimeMillis());
            if (wait > 0) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
                errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.API_RATE_LIMITED);
                return;
            }
            AuthenticatedUser principal = sessionUsers.principalOf(r.user());
            SecurityContextHolder.getContext().setAuthentication(sessionUsers.authenticationOf(principal));
            SessionUserFilter.scope(principal);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
                RequestContext.clearTenant();
                MDC.remove(RequestIdFilter.MDC_FIRMA_ID);
                MDC.remove(RequestIdFilter.MDC_USER_ID);
            }
        } finally {
            record(r, request, response.getStatus(), started, now);
        }
    }

    /** The token, its user and firma, or null when the token cannot be used. */
    private Resolved resolve(String raw, Instant now) {
        if (!raw.startsWith(ApiTokenService.PREFIX) || raw.length() > 100) {
            return null;
        }
        try {
            return RequestContext.runAsRoot(null, () -> tx.execute(s -> {
                ApiToken t = tokens.findByTokenHash(ApiTokenService.hash(raw)).orElse(null);
                if (t == null || t.getRevokedAt() != null
                        || (t.getExpiresAt() != null && !t.getExpiresAt().isAfter(now))) {
                    return null;
                }
                AppUser user = users.findById(t.getUserId()).orElse(null);
                if (user == null || user.getRole() == Role.SUPERUSER
                        || !t.getFirmaId().equals(user.getFirmaId())
                        || sessionUsers.rejectionOf(user, now).isPresent()) {
                    return null;
                }
                Firma firma = firmas.findById(t.getFirmaId()).orElse(null);
                return firma == null ? null : new Resolved(t, user, firma);
            }));
        } catch (Exception e) {
            log.warn("API token lookup failed: {}", e.toString());
            return null;
        }
    }

    private void record(Resolved r, HttpServletRequest request, int status, long started, Instant now) {
        try {
            RequestContext.runAsFirma(r.firma().getId(), r.user().getId(), () -> tx.execute(s -> {
                ApiCall c = new ApiCall();
                c.setFirmaId(r.firma().getId());
                c.setTokenId(r.token().getId());
                c.setUserId(r.user().getId());
                c.setMethod(request.getMethod());
                String path = request.getRequestURI();
                c.setPath(path.length() > 255 ? path.substring(0, 255) : path);
                c.setStatus(status);
                c.setDurationMs((int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000));
                String ip = request.getRemoteAddr();
                c.setIp(ip == null || ip.length() > 45 ? null : ip);
                calls.save(c);
                Instant last = r.token().getLastUsedAt();
                if (last == null || last.isBefore(now.minus(Duration.ofMinutes(1)))) {
                    tokens.findById(r.token().getId()).ifPresent(t -> {
                        t.setLastUsedAt(now);
                        tokens.save(t);
                    });
                }
                return null;
            }));
        } catch (Exception e) {
            log.warn("Could not record the API call: {}", e.toString());
        }
    }
}
