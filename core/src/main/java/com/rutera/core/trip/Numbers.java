package com.rutera.core.trip;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Numbers of orders and trips: what the user typed (trimmed, upper-case, up to 32 characters) or the next free one
 * of the year, {@code O-2026-0001} / {@code T-2026-0001}. The unique keys in MySQL settle two saves at the same time.
 */
final class Numbers {

    static final String ORDER_PREFIX = "O";
    static final String TRIP_PREFIX = "T";
    private static final Pattern SHAPE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ./_-]*");

    private Numbers() {
    }

    /** "O-2026-" for prefix O and year 2026: the LIKE pattern is this plus "%". */
    static String yearPrefix(String prefix, int year) {
        return prefix + "-" + year + "-";
    }

    /** The next number after the highest numeric suffix among {@code existing} (all starting with yearPrefix). */
    static String next(String yearPrefix, List<String> existing) {
        int max = 0;
        for (String number : existing) {
            String tail = number.substring(yearPrefix.length());
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit) && tail.length() < 10) {
                max = Math.max(max, Integer.parseInt(tail));
            }
        }
        return yearPrefix + String.format(Locale.ROOT, "%04d", max + 1);
    }

    /** The typed number, normalised; null when blank. */
    static String typed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    static boolean valid(String number) {
        return number.length() <= 32 && SHAPE.matcher(number).matches();
    }
}
