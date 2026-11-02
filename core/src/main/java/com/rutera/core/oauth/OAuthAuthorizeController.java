package com.rutera.core.oauth;

import com.rutera.common.domain.Firma;
import com.rutera.common.domain.OAuthClient;
import com.rutera.common.i18n.LangService;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Role;
import com.rutera.core.auth.AuthService;
import com.rutera.core.auth.LoginRequest;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /oauth/authorize}: the page where a user connects an OAuth client (claude.ai, Claude Code) to RUTERA. It
 * runs in the session security chain: an existing session of the React client is reused, otherwise the page signs
 * the user in itself ({@link AuthService#login}, lockout included), then asks for consent. Allow redirects to the
 * client with a code (and {@code state}, {@code iss}); Deny with {@code error=access_denied}.
 * <p>
 * An unknown client or a redirect URI that is not registered is shown on the page and never redirected to (RFC 6749
 * section 4.1.2.1); other faults of the request go back to the client as {@code error=...}. PKCE with S256 is
 * required. The forms post back here with the request parameters and the CSRF token ({@code _csrf}).
 */
@RestController
public class OAuthAuthorizeController {

    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; "
            + "base-uri 'none'";

    private final OAuthService oauth;
    private final OAuthUrls urls;
    private final AuthService auth;
    private final FirmaRepository firmas;
    private final LangService lang;
    private final LocaleResolver localeResolver;

    public OAuthAuthorizeController(OAuthService oauth, OAuthUrls urls, AuthService auth, FirmaRepository firmas,
                                    LangService lang, LocaleResolver localeResolver) {
        this.oauth = oauth;
        this.urls = urls;
        this.auth = auth;
        this.firmas = firmas;
        this.lang = lang;
        this.localeResolver = localeResolver;
    }

    /** The parameters of the authorization request, carried through the forms. */
    record Params(String clientId, String redirectUri, String responseType, String codeChallenge,
                  String codeChallengeMethod, String state, String scope, String resource) {

        static Params of(HttpServletRequest r) {
            return new Params(r.getParameter("client_id"), r.getParameter("redirect_uri"),
                    r.getParameter("response_type"), r.getParameter("code_challenge"),
                    r.getParameter("code_challenge_method"), r.getParameter("state"), r.getParameter("scope"),
                    r.getParameter("resource"));
        }

        Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("client_id", clientId);
            m.put("redirect_uri", redirectUri);
            m.put("response_type", responseType);
            m.put("code_challenge", codeChallenge);
            m.put("code_challenge_method", codeChallengeMethod);
            m.put("state", state);
            m.put("scope", scope);
            m.put("resource", resource);
            return m;
        }
    }

    /** A checked request: the client and the redirect URI to use, or what went wrong. */
    private record Checked(OAuthClient client, String redirectUri, ResponseEntity<String> failure) {
    }

    @GetMapping("/oauth/authorize")
    public ResponseEntity<String> show(HttpServletRequest request) {
        Params p = Params.of(request);
        Checked c = check(p, request);
        return c.failure() != null ? c.failure() : screen(p, c, request, null, null);
    }

    @PostMapping(value = "/oauth/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> decide(HttpServletRequest request, HttpServletResponse response) {
        Params p = Params.of(request);
        Checked c = check(p, request);
        if (c.failure() != null) {
            return c.failure();
        }
        String action = Objects.requireNonNullElse(request.getParameter("action"), "");
        switch (action) {
            case "login" -> {
                String login = Objects.requireNonNullElse(request.getParameter("login"), "");
                String password = Objects.requireNonNullElse(request.getParameter("password"), "");
                try {
                    auth.login(new LoginRequest(login, password), request, response);
                } catch (ApiException e) {
                    return screen(p, c, request, text(e.getCode(), request), login);
                }
                return seeOther(request, p);
            }
            case "logout" -> {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                return seeOther(request, p);
            }
            case "deny" -> {
                return failure(c.redirectUri(), p, request, "access_denied", "The user denied the request");
            }
            case "allow" -> {
                AuthenticatedUser user = CurrentUser.get().orElse(null);
                if (user == null || !canConnect(user) || !aiAccess(user)) {
                    return screen(p, c, request, null, null);
                }
                String code = oauth.issueCode(user, c.client(), c.redirectUri(), p.codeChallenge(), p.resource());
                return redirect(c.redirectUri(), p, request, Map.of("code", code));
            }
            default -> {
                return screen(p, c, request, null, null);
            }
        }
    }

    // --- request checks ------------------------------------------------------------------------------------

    private Checked check(Params p, HttpServletRequest request) {
        OAuthClient client = oauth.client(p.clientId()).orElse(null);
        if (client == null) {
            return new Checked(null, null, failed(request));
        }
        List<String> registered = oauth.redirectUris(client);
        String redirectUri = p.redirectUri();
        if (redirectUri == null && registered.size() == 1) {
            redirectUri = registered.getFirst();
        }
        if (redirectUri == null || !registered.contains(redirectUri)) {
            return new Checked(null, null, failed(request));
        }
        if (!"code".equals(p.responseType())) {
            return new Checked(client, redirectUri, failure(redirectUri, p, request, "unsupported_response_type",
                    "response_type must be code"));
        }
        if (!"S256".equals(p.codeChallengeMethod()) || !OAuthService.validChallenge(p.codeChallenge())) {
            return new Checked(client, redirectUri, failure(redirectUri, p, request, "invalid_request",
                    "PKCE with code_challenge_method S256 is required"));
        }
        if (p.resource() != null && !sameServer(p.resource(), urls.base(request))) {
            return new Checked(client, redirectUri, failure(redirectUri, p, request, "invalid_target",
                    "resource is not this server"));
        }
        return new Checked(client, redirectUri, null);
    }

    /** Is {@code resource} on this server (the base itself or a path below it)? */
    static boolean sameServer(String resource, String base) {
        try {
            URI r = new URI(resource);
            URI b = new URI(base);
            return r.getScheme() != null && r.getScheme().equalsIgnoreCase(b.getScheme())
                    && r.getHost() != null && r.getHost().equalsIgnoreCase(b.getHost())
                    && r.getPort() == b.getPort() && r.getRawFragment() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canConnect(AuthenticatedUser user) {
        return user.firmaId() != null && user.role() != Role.SUPERUSER;
    }

    private boolean aiAccess(AuthenticatedUser user) {
        return firmas.findById(user.firmaId()).map(Firma::isAiAccess).orElse(false);
    }

    // --- screens -------------------------------------------------------------------------------------------

    private ResponseEntity<String> failed(HttpServletRequest request) {
        String body = OAuthPage.error(text("oauth.error_client", request));
        return html(HttpStatus.BAD_REQUEST, request, text("oauth.error_title", request), body);
    }

    /** Sign-in when there is no session, else consent (or why the account cannot connect). */
    private ResponseEntity<String> screen(Params p, Checked c, HttpServletRequest request, String error,
                                          String login) {
        Map<String, String> hidden = new LinkedHashMap<>(p.asMap());
        hidden.put("redirect_uri", c.redirectUri());
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) {
            hidden.put(csrf.getParameterName(), csrf.getToken());
        }
        AuthenticatedUser user = CurrentUser.get().orElse(null);
        String clientRow = c.client().getClientName();
        String returnRow = URI.create(c.redirectUri()).getHost();
        if (user == null) {
            String body = OAuthPage.muted(text("oauth.sign_in_hint", request))
                    + OAuthPage.details(Map.of(text("oauth.application", request), clientRow))
                    + OAuthPage.error(error)
                    + OAuthPage.form(hidden,
                    OAuthPage.input("text", "login", text("auth.login.login", request), login, "username")
                            + OAuthPage.input("password", "password", text("auth.login.password", request), null,
                            "current-password")
                            + OAuthPage.buttons(OAuthPage.button("login", text("auth.login.submit", request), "primary"),
                            OAuthPage.button("deny", text("oauth.deny", request), null)));
            return html(HttpStatus.OK, request, text("oauth.sign_in_title", request), body);
        }
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(text("oauth.application", request), clientRow);
        rows.put(text("oauth.account", request), user.login());
        rows.put(text("oauth.return_to", request), returnRow);
        String otherAccount = OAuthPage.button("logout", text("oauth.other_account", request), "link");
        if (!canConnect(user) || !aiAccess(user)) {
            String reason = canConnect(user) ? text(ErrorCodes.API_AI_ACCESS_REQUIRED, request)
                    : text("oauth.error_account", request);
            String body = OAuthPage.details(rows) + OAuthPage.error(reason) + OAuthPage.form(hidden,
                    OAuthPage.buttons(OAuthPage.button("deny", text("oauth.deny", request), null)) + otherAccount);
            return html(HttpStatus.FORBIDDEN, request, text("oauth.error_title", request), body);
        }
        String body = OAuthPage.details(rows) + OAuthPage.text(text("oauth.consent_text", request))
                + OAuthPage.error(error) + OAuthPage.form(hidden,
                OAuthPage.buttons(OAuthPage.button("allow", text("oauth.allow", request), "primary"),
                        OAuthPage.button("deny", text("oauth.deny", request), null)) + otherAccount);
        return html(HttpStatus.OK, request, text("oauth.consent_title", request), body);
    }

    private ResponseEntity<String> html(HttpStatus status, HttpServletRequest request, String title, String body) {
        Locale locale = localeResolver.resolveLocale(request);
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CSP)
                .header("Referrer-Policy", "no-referrer")
                .body(OAuthPage.page(locale.getLanguage(), title, body));
    }

    private String text(String key, HttpServletRequest request) {
        return lang.get(key, localeResolver.resolveLocale(request));
    }

    // --- redirects -----------------------------------------------------------------------------------------

    /** Back to this page with the same request (after sign-in or sign-out), as a GET. */
    private static ResponseEntity<String> seeOther(HttpServletRequest request, Params p) {
        StringBuilder url = new StringBuilder("/oauth/authorize");
        char sep = '?';
        for (Map.Entry<String, String> e : p.asMap().entrySet()) {
            if (e.getValue() != null) {
                url.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
                sep = '&';
            }
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, url.toString())
                .cacheControl(CacheControl.noStore()).build();
    }

    /** To the client with {@code error} and {@code error_description} (RFC 6749 section 4.1.2.1). */
    private ResponseEntity<String> failure(String redirectUri, Params p, HttpServletRequest request, String error,
                                           String description) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        return redirect(redirectUri, p, request, params);
    }

    /** To the client: the given parameters plus {@code state} and {@code iss} (RFC 9207). */
    private ResponseEntity<String> redirect(String redirectUri, Params p, HttpServletRequest request,
                                            Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        if (p.state() != null) {
            all.put("state", p.state());
        }
        all.put("iss", urls.base(request));
        StringBuilder url = new StringBuilder(redirectUri);
        char sep = redirectUri.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : all.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.toString())
                .cacheControl(CacheControl.noStore()).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
