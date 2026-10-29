package com.rutera.core.config;

import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.core.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Enumeration;
import java.util.Locale;

/**
 * Language of a request: the signed-in user's language, otherwise the first supported language from
 * {@code Accept-Language}, otherwise English. Registered under the name Spring MVC looks for
 * ({@code localeResolver}), so {@code LocaleContextHolder} and {@code LangService.get(key)} follow it.
 */
@Component("localeResolver")
public class UserLocaleResolver implements LocaleResolver {

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return CurrentUser.get()
                .map(user -> SupportedLanguages.toLocale(user.lang()))
                .orElseGet(() -> fromHeader(request));
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("The language is a user setting (PUT /app/me/settings)");
    }

    private static Locale fromHeader(HttpServletRequest request) {
        Enumeration<Locale> locales = request.getLocales();
        if (request.getHeader("Accept-Language") != null) {
            while (locales.hasMoreElements()) {
                Locale candidate = locales.nextElement();
                if (SupportedLanguages.isSupported(candidate.getLanguage())) {
                    return SupportedLanguages.toLocale(candidate.getLanguage());
                }
            }
        }
        return Locale.ENGLISH;
    }
}
