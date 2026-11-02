package com.rutera.core.fuel.read;

import com.rutera.common.domain.FuelTransaction.Product;
import com.rutera.common.domain.FuelTransaction.Unit;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing of the cell values of provider exports: amounts, dates, products, units, countries and card numbers. */
public final class FuelValues {

    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})|(\\d{1,2})[-./](\\d{1,2})[-./](\\d{2,4})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Map<String, String> ISO3 = new HashMap<>();
    private static final Map<String, String> CURRENCY_SIGNS = Map.of("€", "EUR", "ZŁ", "PLN", "ZL", "PLN", "£", "GBP",
            "KČ", "CZK", "KC", "CZK", "FT", "HUF", "$", "USD", "LEI", "RON");
    private static final List<Map.Entry<Product, List<String>>> PRODUCT_WORDS = List.of(
            Map.entry(Product.TOLL, List.of("toll", "maut", "myto", "peage", "vignette", "viapass", "telepass",
                    "mautgebuhr", "oplata drogowa", "autostrada")),
            Map.entry(Product.ADBLUE, List.of("adblue", "ad blue", "arla", "def", "urea", "harnstoff")),
            Map.entry(Product.LNG, List.of("lng")),
            Map.entry(Product.CNG, List.of("cng")),
            Map.entry(Product.DIESEL, List.of("diesel", "gazole", "gasoil", "gas oil", "olej napedowy", "on", "dk",
                    "b7", "hvo", "nafta", "motorina", "dieselkraftstoff")),
            Map.entry(Product.PETROL, List.of("petrol", "benzin", "benzyna", "gasoline", "super", "pb95", "pb98",
                    "pb 95", "pb 98", "e10", "euro 95", "essence", "sp95", "sp98", "unleaded", "bleifrei")),
            Map.entry(Product.SERVICE, List.of("wash", "wasche", "myjnia", "lavage", "service", "parking", "repair",
                    "werkstatt", "naprawa", "oil", "olej silnikowy", "motorol", "tire", "reifen", "opona")));

    static {
        for (String code : Locale.getISOCountries()) {
            ISO3.put(Locale.of("", code).getISO3Country(), code);
        }
    }

    private FuelValues() {
    }

    /** Lower case, no diacritics, only letters and digits separated by single spaces. */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L').replace('ß', 's').replace('ø', 'o').replace('đ', 'd');
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * An amount: spaces, apostrophes and currency signs are ignored; with both separators the last one is the decimal
     * one; a lone comma is decimal unless {@code decimalComma} is false, a lone point is decimal unless
     * {@code decimalComma} is true. Null for an empty cell; {@link NumberFormatException} for anything else.
     */
    public static BigDecimal decimal(String raw, Boolean decimalComma) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s  '’]", "").replaceAll("[^0-9,.\\-+]", "");
        if (s.isEmpty() || s.equals("-") || s.equals("+")) {
            if (raw.isBlank()) {
                return null;
            }
            throw new NumberFormatException(raw);
        }
        boolean negative = s.startsWith("-") || s.endsWith("-");
        s = s.replace("-", "").replace("+", "");
        int comma = s.lastIndexOf(',');
        int point = s.lastIndexOf('.');
        if (comma >= 0 && point >= 0) {
            s = comma > point ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            boolean decimal = !Boolean.FALSE.equals(decimalComma) && s.indexOf(',') == comma;
            s = decimal ? s.replace(',', '.') : s.replace(",", "");
        } else if (point >= 0 && (Boolean.TRUE.equals(decimalComma) || s.indexOf('.') != point)) {
            s = s.replace(".", "");
        }
        BigDecimal value = new BigDecimal(s);
        return negative ? value.negate() : value;
    }

    /**
     * Date and time of a transaction: the date cell may carry the time; else the time cell. Days come before months
     * unless the year comes first; {@code pattern} (a {@link DateTimeFormatter} pattern) overrides the guess.
     */
    public static LocalDateTime dateTime(String dateCell, String timeCell, String pattern) {
        if (dateCell == null || dateCell.isBlank()) {
            throw new DateTimeParseException("empty date", "", 0);
        }
        if (pattern != null && !pattern.isBlank()) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.SMART);
            String text = dateCell.strip();
            try {
                return LocalDateTime.parse(text, f);
            } catch (DateTimeParseException e) {
                return LocalDate.parse(text, f).atTime(time(timeCell));
            }
        }
        Matcher d = DATE.matcher(dateCell);
        if (!d.find()) {
            throw new DateTimeParseException("no date", dateCell, 0);
        }
        int year;
        int month;
        int day;
        if (d.group(1) != null) {
            year = Integer.parseInt(d.group(1));
            month = Integer.parseInt(d.group(2));
            day = Integer.parseInt(d.group(3));
        } else {
            day = Integer.parseInt(d.group(4));
            month = Integer.parseInt(d.group(5));
            year = Integer.parseInt(d.group(6));
            year = year < 100 ? 2000 + year : year;
        }
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new DateTimeParseException("bad date", dateCell, 0);
        }
        String rest = dateCell.substring(d.end());
        LocalTime time = TIME.matcher(rest).find() ? time(rest) : time(timeCell);
        return date.atTime(time);
    }

    /** HH:mm[:ss] anywhere in the cell (an XLSX time arrives as 1899-12-30 HH:mm:ss); midnight when empty. */
    static LocalTime time(String cell) {
        if (cell == null || cell.isBlank()) {
            return LocalTime.MIDNIGHT;
        }
        Matcher t = TIME.matcher(cell);
        if (!t.find()) {
            throw new DateTimeParseException("bad time", cell, 0);
        }
        int h = Integer.parseInt(t.group(1));
        int m = Integer.parseInt(t.group(2));
        int s = t.group(3) == null ? 0 : Integer.parseInt(t.group(3));
        if (h > 23 || m > 59 || s > 59) {
            throw new DateTimeParseException("bad time", cell, 0);
        }
        return LocalTime.of(h, m, s);
    }

    /** The product group of a product text by keywords (whole words); OTHER when nothing matches. */
    public static Product product(String raw) {
        String text = " " + fold(raw) + " ";
        for (Map.Entry<Product, List<String>> e : PRODUCT_WORDS) {
            for (String word : e.getValue()) {
                if (text.contains(" " + word + " ")) {
                    return e.getKey();
                }
            }
        }
        return Product.OTHER;
    }

    /** L, KG or PCS from a unit text; null when unknown. */
    public static Unit unit(String raw) {
        return switch (fold(raw)) {
            case "l", "ltr", "lt", "litre", "liter", "litr", "litry", "litres", "liters", "litri" -> Unit.L;
            case "kg", "kilogram" -> Unit.KG;
            case "st", "stk", "stuck", "pcs", "pc", "szt", "sztuk", "pce", "piece", "pieces", "unit", "ks" -> Unit.PCS;
            default -> null;
        };
    }

    /** ISO 3166 alpha-2 from a 2- or 3-letter code; null when unknown. */
    public static String country(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (s.length() == 2 && s.chars().allMatch(Character::isLetter)) {
            return s;
        }
        if (s.length() == 3) {
            return ISO3.get(s);
        }
        return null;
    }

    /** A three-letter currency code, also from the common signs (€, zł, Kč, Ft, £); null when unknown. */
    public static String currency(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (s.matches("[A-Z]{3}")) {
            return s;
        }
        return CURRENCY_SIGNS.get(s);
    }

    /** Upper-case letters, digits and the mask stars of a card number (exports often mask it already). */
    public static String cardNumber(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9*]", "");
    }

    /** For display: the first four and the last four characters, the rest hidden. */
    public static String maskCard(String normalized) {
        if (normalized.length() <= 8) {
            return "****" + normalized.substring(Math.max(0, normalized.length() - 4));
        }
        return normalized.substring(0, 4) + " **** " + normalized.substring(normalized.length() - 4);
    }
}
