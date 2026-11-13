package com.rutera.core.explain;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts of the explanation PDF per language: the researched templates (research/driver-explanations, DRAFT, not
 * legally reviewed) in {@code resources/explanations/explanation_<lang>.properties}, UTF-8, keys
 * {@code explanation.*}, placeholders {@code {{name}}} replaced here (never through MessageFormat). The language
 * follows the firma's country of registration ({@link #languageOf}, as country-locale-map.csv); a country without its
 * own template gets the English one. Adding a language is adding a file.
 */
@Component
public class ExplanationTemplates {

    static final String FALLBACK = "en";
    /** What an unknown placeholder prints: a line to fill by hand. */
    static final String BLANK = "……………";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9.]+)\\}\\}");
    private static final Map<String, String> LANGUAGE_OF_COUNTRY = Map.ofEntries(
            Map.entry("PL", "pl"), Map.entry("DE", "de"), Map.entry("AT", "de"), Map.entry("CH", "de"),
            Map.entry("LI", "de"), Map.entry("LU", "de"), Map.entry("CZ", "cs"), Map.entry("SK", "sk"),
            Map.entry("HU", "hu"), Map.entry("RO", "ro"), Map.entry("BG", "bg"), Map.entry("LT", "lt"),
            Map.entry("LV", "lv"), Map.entry("EE", "et"), Map.entry("FI", "fi"), Map.entry("SE", "sv"),
            Map.entry("DK", "da"), Map.entry("NL", "nl"), Map.entry("BE", "nl"), Map.entry("FR", "fr"),
            Map.entry("IT", "it"), Map.entry("ES", "es"), Map.entry("PT", "pt"), Map.entry("SI", "sl"),
            Map.entry("HR", "hr"), Map.entry("GR", "el"), Map.entry("CY", "el"), Map.entry("MT", "en"),
            Map.entry("IE", "en"), Map.entry("GB", "en"), Map.entry("UA", "uk"), Map.entry("NO", "nb"),
            Map.entry("IS", "is"));

    private final Map<String, Properties> cache = new ConcurrentHashMap<>();

    /** The official language of a country of registration (ISO 3166-1 alpha-2); English when unknown. */
    public static String languageOf(String country) {
        return country == null ? FALLBACK : LANGUAGE_OF_COUNTRY.getOrDefault(country.toUpperCase(Locale.ROOT), FALLBACK);
    }

    /** The language the PDF is written in for a country: its own language when there is a template, else English. */
    public String documentLanguage(String country) {
        String lang = languageOf(country);
        return load(lang).isEmpty() ? FALLBACK : lang;
    }

    /** The template of a language (the English one when there is none). */
    public Template template(String lang) {
        Properties p = load(lang);
        return new Template(p.isEmpty() ? load(FALLBACK) : p);
    }

    private Properties load(String lang) {
        return cache.computeIfAbsent(lang == null ? FALLBACK : lang, l -> {
            Properties p = new Properties();
            if (!l.matches("[a-z]{2,3}")) {
                return p;
            }
            try (InputStream in = getClass().getResourceAsStream("/explanations/explanation_" + l + ".properties")) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                return p;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The texts of one language. */
    public record Template(Properties texts) {

        /** The text of {@code explanation.<key>}, empty when the template lacks it. */
        public String text(String key) {
            return texts.getProperty("explanation." + key, "");
        }

        public boolean has(String key) {
            return !text(key).isEmpty();
        }

        /** The text with {{name}} placeholders replaced; unknown or empty values print as a line to fill in. */
        public String fill(String key, Map<String, String> values) {
            Matcher m = PLACEHOLDER.matcher(text(key));
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String v = values.get(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(v == null || v.isBlank() ? BLANK : v));
            }
            m.appendTail(out);
            return out.toString();
        }

        /** Minutes as the template writes durations ("{h} godz. {m} min"), a zero part left out ("25 min"). */
        public String duration(int minutes) {
            String pattern = text("format.duration");
            if (pattern.isEmpty()) {
                pattern = "{h} h {m} min";
            }
            int h = Math.abs(minutes) / 60;
            int m = Math.abs(minutes) % 60;
            int split = pattern.indexOf("{m}");
            String hours = split > 0 ? pattern.substring(0, split).strip() : pattern;
            String mins = split >= 0 ? pattern.substring(split) : pattern;
            String chosen = h == 0 ? mins : m == 0 ? hours : pattern;
            return chosen.replace("{h}", Integer.toString(h)).replace("{m}", Integer.toString(m));
        }

        public String date(ZonedDateTime at) {
            return DateTimeFormatter.ofPattern(or(text("format.date"), "dd.MM.yyyy")).format(at);
        }

        public String time(ZonedDateTime at) {
            return DateTimeFormatter.ofPattern(or(text("format.time"), "HH:mm")).format(at);
        }

        private static String or(String s, String fallback) {
            return s.isEmpty() ? fallback : s;
        }
    }
}
