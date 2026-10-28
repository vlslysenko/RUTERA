package com.rutera.common.i18n;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Texts for the server side (error messages, e-mails, audit summaries). The frontend gets the same keys as
 * a JSON dictionary. Modelled on SMDP LangService without the file-based system language: the language
 * comes from the user, the firma or the browser (see the web layer's locale resolver).
 */
@Service
public class LangService {

    private final ExposedMessageSource messageSource;

    public LangService(ExposedMessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Text in the locale of the current request. */
    public String get(String key, Object... args) {
        return get(key, LocaleContextHolder.getLocale(), args);
    }

    public String get(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale == null ? Locale.ENGLISH : locale);
    }

    public String get(String key, String langCode, Object... args) {
        return get(key, SupportedLanguages.toLocale(langCode), args);
    }

    /** Whole dictionary of a language, with English fallbacks for missing keys. */
    public Map<String, String> dictionary(String langCode) {
        return messageSource.dictionary(SupportedLanguages.toLocale(langCode));
    }
}
