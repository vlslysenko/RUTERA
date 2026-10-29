package com.rutera.core.security;

import com.rutera.common.i18n.LangService;
import com.rutera.core.web.ErrorBody;
import com.rutera.core.web.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * JSON answers for the two failures raised inside the security filter chain, where the MVC exception handler
 * is not reachable: no session (401 {@code auth.unauthenticated}) and access denied (403, which for a missing or
 * stale CSRF token is {@code auth.csrf_invalid}, otherwise {@code auth.access_denied}).
 */
@Component
public class JsonSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final LangService lang;
    private final LocaleResolver localeResolver;

    public JsonSecurityErrors(ObjectMapper objectMapper, LangService lang, LocaleResolver localeResolver) {
        this.objectMapper = objectMapper;
        this.lang = lang;
        this.localeResolver = localeResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        String code = ex instanceof CsrfException ? ErrorCodes.CSRF_INVALID : ErrorCodes.ACCESS_DENIED;
        write(request, response, HttpStatus.FORBIDDEN, code);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Locale locale = localeResolver.resolveLocale(request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorBody.of(code, lang.get(code, locale)));
        response.flushBuffer();
    }
}
