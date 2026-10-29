package com.rutera.core.auth;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.security.SessionUserFilter;
import com.rutera.core.security.SessionUserService;
import com.rutera.core.user.UserDto;
import com.rutera.core.user.UserSettings;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * JSON sign-in for the React client. Checks, in this order: login known, account not locked, password,
 * account enabled, firma active. The first two keep the account state hidden behind
 * {@code auth.login.invalid} until the caller has proven the password, except for the lockout, which has to
 * stop password guessing before the hash is computed.
 * <p>
 * On success the request is bound to the user: session id rotated (fixation), authentication stored in the
 * session, CSRF token rotated, {@code RequestContext} scoped, audit entry written.
 */
@Service
public class AuthService {

    public static final String MODULE = "auth";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** Hash of an unguessable value, matched against unknown logins so that timing does not reveal them. */
    private static final String DUMMY_PASSWORD = "rutera-no-such-account";

    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final PasswordEncoder passwordEncoder;
    private final SessionUserService sessionUsers;
    private final LoginAttemptService attempts;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final ObjectMapper objectMapper;
    private final String dummyHash;

    public AuthService(AppUserRepository users, FirmaRepository firmas, PasswordEncoder passwordEncoder,
                       SessionUserService sessionUsers, LoginAttemptService attempts,
                       SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
                       ObjectMapper objectMapper) {
        this.users = users;
        this.firmas = firmas;
        this.passwordEncoder = passwordEncoder;
        this.sessionUsers = sessionUsers;
        this.attempts = attempts;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.objectMapper = objectMapper;
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    public UserDto login(LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        Instant now = Instant.now();
        String login = request.login().trim().toLowerCase(Locale.ROOT);
        Optional<AppUser> found = users.findByLoginAndDeletedFalse(login);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            attempts.unknownLogin(login);
            throw ApiException.unauthorized(ErrorCodes.LOGIN_INVALID);
        }
        AppUser user = found.get();
        if (user.isLockedAt(now)) {
            attempts.rejected(user, "locked");
            throw ApiException.unauthorized(ErrorCodes.LOGIN_LOCKED);
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            attempts.wrongPassword(user, now);
            throw ApiException.unauthorized(ErrorCodes.LOGIN_INVALID);
        }
        Optional<SessionUserService.Rejection> rejection = sessionUsers.rejectionOf(user, now);
        if (rejection.isPresent()) {
            String code = switch (rejection.get()) {
                case DISABLED -> ErrorCodes.LOGIN_DISABLED;
                case LOCKED -> ErrorCodes.LOGIN_LOCKED;
                case FIRMA_INACTIVE -> ErrorCodes.LOGIN_FIRMA_INACTIVE;
            };
            attempts.rejected(user, rejection.get().name().toLowerCase(Locale.ROOT));
            throw ApiException.unauthorized(code);
        }

        AuthenticatedUser principal = sessionUsers.principalOf(user);
        establishSession(principal, http, response);
        attempts.succeeded(user);
        log.info("User {} signed in", user.getId());
        return toDto(user, principal);
    }

    /** {@code GET /app/auth/me}: current user, with the session snapshot refreshed from the database. */
    public UserDto me(HttpServletRequest http, HttpServletResponse response) {
        AuthenticatedUser current = CurrentUser.require();
        AppUser user = users.findById(current.id())
                .orElseThrow(() -> ApiException.unauthorized(ErrorCodes.UNAUTHENTICATED));
        AuthenticatedUser refreshed = sessionUsers.principalOf(user);
        if (!refreshed.equals(current)) {
            storeAuthentication(refreshed, http, response);
            SessionUserFilter.scope(refreshed);
        }
        return toDto(user, refreshed);
    }

    /** Replaces the session principal after a change of the user's own data (language, settings). */
    public AuthenticatedUser refreshPrincipal(AppUser user, HttpServletRequest http, HttpServletResponse response) {
        AuthenticatedUser refreshed = sessionUsers.principalOf(user);
        storeAuthentication(refreshed, http, response);
        SessionUserFilter.scope(refreshed);
        return refreshed;
    }

    public UserDto toDto(AppUser user, AuthenticatedUser principal) {
        Firma firma = user.getFirmaId() == null ? null : firmas.findById(user.getFirmaId()).orElse(null);
        return UserDto.of(user, firma, UserSettings.parse(objectMapper, user.getSettings()), principal.privileges());
    }

    private void establishSession(AuthenticatedUser principal, HttpServletRequest http, HttpServletResponse response) {
        if (http.getSession(false) != null) {
            http.changeSessionId();
        }
        storeAuthentication(principal, http, response);
        SessionUserFilter.scope(principal);
        CsrfToken fresh = csrfTokenRepository.generateToken(http);
        csrfTokenRepository.saveToken(fresh, http, response);
    }

    private void storeAuthentication(AuthenticatedUser principal, HttpServletRequest http, HttpServletResponse response) {
        Authentication authentication = sessionUsers.authenticationOf(principal);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, http, response);
    }
}
