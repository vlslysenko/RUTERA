package com.rutera.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * CSRF handler for the React client (the pattern from the Spring Security reference, "Single-Page Applications").
 * <ul>
 *   <li>The token is loaded on every request, so {@code CookieCsrfTokenRepository} writes the {@code XSRF-TOKEN}
 *       cookie as soon as the client makes its first call (for example {@code GET /app/auth/me}).</li>
 *   <li>The client copies the cookie value into the {@code X-XSRF-TOKEN} header unchanged; a header value is
 *       therefore compared as a plain token, while a form parameter would still be BREACH-protected (XOR).</li>
 * </ul>
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        // Force the deferred token to load: on a missing cookie the repository generates and sets it now.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
