package com.rutera.core.explain;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The texts of the explanation PDF per language ({@code resources/explanations/explanation_<lang>.properties},
 * UTF-8). The language follows the firma's country of registration ({@link #languageOf}); a country without its own
 * template gets the English one. Adding a language is adding a file.
 */
@Component
public class ExplanationTemplates {

    static final String FALLBACK = "en";
    private static final Map<String, String> LANGUAGE_OF_COUNTRY = Map.ofEntries(
            Map.entry("PL", "pl"), Map.entry("DE", "de"), Map.entry("AT", "de"), Map.entry("CH", "de"),
            Map.entry("LI", "de"), Map.entry("CZ", "cs"), Map.entry("SK", "sk"), Map.entry("HU", "hu"),
            Map.entry("RO", "ro"), Map.entry("BG", "bg"), Map.entry("LT", "lt"), Map.entry("LV", "lv"),
            Map.entry("EE", "et"), Map.entry("FI", "fi"), Map.entry("SE", "sv"), Map.entry("DK", "da"),
            Map.entry("NL", "nl"), Map.entry("BE", "nl"), Map.entry("LU", "fr"), Map.entry("FR", "fr"),
            Map.entry("IT", "it"), Map.entry("ES", "es"), Map.entry("PT", "pt"), Map.entry("SI", "sl"),
            Map.entry("HR", "hr"), Map.entry("GR", "el"), Map.entry("CY", "el"), Map.entry("MT", "mt"),
            Map.entry("IE", "en"), Map.entry("GB", "en"), Map.entry("UA", "uk"), Map.entry("NO", "nb"));

    private final Map<String, Properties> cache = new ConcurrentHashMap<>();

    /** The official language of a country of registration (ISO 3166-1 alpha-2); English when unknown. */
    public static String languageOf(String country) {
        return country == null ? FALLBACK : LANGUAGE_OF_COUNTRY.getOrDefault(country.toUpperCase(Locale.ROOT), FALLBACK);
    }

    /** The language the PDF is written in for a country: its own language when there is a template, else English. */
    public String documentLanguage(String country) {
        String lang = languageOf(country);
        return load(lang) != null ? lang : FALLBACK;
    }

    /** The template of a language (the English one when there is none). */
    public Template template(String lang) {
        Properties p = load(lang);
        return new Template(p != null ? p : load(FALLBACK));
    }

    private Properties load(String lang) {
        return cache.computeIfAbsent(lang, l -> {
            try (InputStream in = getClass().getResourceAsStream("/explanations/explanation_" + l + ".properties")) {
                if (in == null) {
                    return new Properties();
                }
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                return p;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).isEmpty() ? null : cache.get(lang);
    }

    /** The texts of one language. */
    public record Template(Properties texts) {

        public String text(String key) {
            return texts.getProperty(key, "");
        }

        /** The text with {name} placeholders replaced. */
        public String fill(String key, Map<String, String> values) {
            String out = text(key);
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? text("none") : e.getValue());
            }
            return out;
        }

        /** A duration in minutes as the template writes it. */
        public String duration(int minutes) {
            return MessageFormat.format(text("duration").replace("'", "''"), minutes / 60, minutes % 60);
        }
    }
}
