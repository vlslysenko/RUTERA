package com.rutera.tacho.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Text fields of tachograph data: IA5 strings (ASCII) and strings with a leading code page byte (Annex IB/IC
 * data dictionary: Name, Address, VehicleRegistrationNumber). Padding (spaces, 00h, FFh) is removed; a blank value
 * is returned as {@code null}.
 */
public final class TachoText {

    private static final Charset[] CODE_PAGES = new Charset[256];

    static {
        for (int i = 0; i < CODE_PAGES.length; i++) {
            CODE_PAGES[i] = StandardCharsets.ISO_8859_1;
        }
        // 1..16: ISO/IEC 8859-n. 12 does not exist; 10 and 14 are missing in some JDKs (then Latin-1).
        for (int n = 1; n <= 16; n++) {
            String name = n == 11 ? "x-iso-8859-11" : "ISO-8859-" + n;
            if (n != 12 && Charset.isSupported(name)) {
                CODE_PAGES[n] = Charset.forName(name);
            }
        }
        if (Charset.isSupported("KOI8-R")) {
            CODE_PAGES[80] = Charset.forName("KOI8-R");
        }
        if (Charset.isSupported("KOI8-U")) {
            CODE_PAGES[85] = Charset.forName("KOI8-U");
        }
    }

    private TachoText() {
    }

    /** Charset of a tachograph code page byte; unknown code pages fall back to ISO 8859-1. */
    public static Charset charset(int codePage) {
        return CODE_PAGES[codePage & 0xFF];
    }

    public static String decode(int codePage, byte[] data, int offset, int length) {
        int[] range = trimRange(data, offset, length);
        if (range == null) {
            return null;
        }
        String s = new String(data, range[0], range[1] - range[0], charset(codePage));
        return clean(s);
    }

    public static String ia5(byte[] data, int offset, int length) {
        int[] range = trimRange(data, offset, length);
        if (range == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(range[1] - range[0]);
        for (int i = range[0]; i < range[1]; i++) {
            int c = data[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : ' ');
        }
        return clean(sb.toString());
    }

    /** [from, to) without leading spaces and trailing spaces, 00h and FFh; null when nothing is left. */
    private static int[] trimRange(byte[] data, int offset, int length) {
        int from = offset;
        int to = offset + length;
        while (to > from && isPadding(data[to - 1])) {
            to--;
        }
        while (from < to && (data[from] == ' ' || data[from] == 0)) {
            from++;
        }
        return from < to ? new int[] {from, to} : null;
    }

    private static boolean isPadding(byte b) {
        return b == ' ' || b == 0 || b == (byte) 0xFF;
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        String t = sb.toString().strip();
        return t.isEmpty() ? null : t;
    }
}
