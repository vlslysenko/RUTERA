package com.rutera.common.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 26 interface languages: 24 official EU languages plus Ukrainian and Russian (REQUIREMENTS.md, section 6).
 * Codes are ISO 639-1; {@code en} is the source language and the fallback.
 */
public final class SupportedLanguages {

    public static final String DEFAULT = "en";

    private static final Map<String, String> NATIVE_NAMES = new LinkedHashMap<>();

    static {
        NATIVE_NAMES.put("bg", "Български");
        NATIVE_NAMES.put("cs", "Čeština");
        NATIVE_NAMES.put("da", "Dansk");
        NATIVE_NAMES.put("de", "Deutsch");
        NATIVE_NAMES.put("el", "Ελληνικά");
        NATIVE_NAMES.put("en", "English");
        NATIVE_NAMES.put("es", "Español");
        NATIVE_NAMES.put("et", "Eesti");
        NATIVE_NAMES.put("fi", "Suomi");
        NATIVE_NAMES.put("fr", "Français");
        NATIVE_NAMES.put("ga", "Gaeilge");
        NATIVE_NAMES.put("hr", "Hrvatski");
        NATIVE_NAMES.put("hu", "Magyar");
        NATIVE_NAMES.put("it", "Italiano");
        NATIVE_NAMES.put("lt", "Lietuvių");
        NATIVE_NAMES.put("lv", "Latviešu");
        NATIVE_NAMES.put("mt", "Malti");
        NATIVE_NAMES.put("nl", "Nederlands");
        NATIVE_NAMES.put("pl", "Polski");
        NATIVE_NAMES.put("pt", "Português");
        NATIVE_NAMES.put("ro", "Română");
        NATIVE_NAMES.put("sk", "Slovenčina");
        NATIVE_NAMES.put("sl", "Slovenščina");
        NATIVE_NAMES.put("sv", "Svenska");
        NATIVE_NAMES.put("uk", "Українська");
        NATIVE_NAMES.put("ru", "Русский");
    }

    /** Language codes in catalogue order. */
    public static final List<String> CODES = List.copyOf(NATIVE_NAMES.keySet());

    private SupportedLanguages() {
    }

    public static boolean isSupported(String code) {
        return code != null && NATIVE_NAMES.containsKey(code.toLowerCase(Locale.ROOT));
    }

    /** Native name of the language, e.g. "Polski" for pl. */
    public static String nativeName(String code) {
        return NATIVE_NAMES.get(code);
    }

    public static Map<String, String> nativeNames() {
        return Map.copyOf(NATIVE_NAMES);
    }

    /** Normalises any code or locale tag ("PL", "pl-PL", "uk_UA") to a supported code, or the default. */
    public static String normalize(String codeOrTag) {
        if (codeOrTag == null || codeOrTag.isBlank()) {
            return DEFAULT;
        }
        String lang = codeOrTag.trim().toLowerCase(Locale.ROOT);
        int cut = indexOfSeparator(lang);
        if (cut > 0) {
            lang = lang.substring(0, cut);
        }
        return NATIVE_NAMES.containsKey(lang) ? lang : DEFAULT;
    }

    public static Locale toLocale(String code) {
        return Locale.forLanguageTag(normalize(code));
    }

    private static int indexOfSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }
}
