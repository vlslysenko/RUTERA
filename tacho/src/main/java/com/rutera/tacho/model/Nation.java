package com.rutera.tacho.model;

import java.util.HashMap;
import java.util.Map;

/**
 * NationNumeric of the data dictionary: the tachograph code of a country ({@code tachoCode}, for example "D",
 * "PL", "UA") and its ISO 3166-1 alpha-2 code ({@code iso2}, null for EC, Europe, World and codes without an ISO
 * country). Unknown codes keep the number with null names.
 */
public record Nation(int code, String tachoCode, String iso2) {

    private static final Map<Integer, Nation> KNOWN = new HashMap<>();

    static {
        String[][] table = {
                {"01", "A", "AT"}, {"02", "AL", "AL"}, {"03", "AND", "AD"}, {"04", "ARM", "AM"},
                {"05", "AZ", "AZ"}, {"06", "B", "BE"}, {"07", "BG", "BG"}, {"08", "BIH", "BA"},
                {"09", "BY", "BY"}, {"0A", "CH", "CH"}, {"0B", "CY", "CY"}, {"0C", "CZ", "CZ"},
                {"0D", "D", "DE"}, {"0E", "DK", "DK"}, {"0F", "E", "ES"}, {"10", "EST", "EE"},
                {"11", "F", "FR"}, {"12", "FIN", "FI"}, {"13", "FL", "LI"}, {"14", "FR", "FO"},
                {"15", "UK", "GB"}, {"16", "GE", "GE"}, {"17", "GR", "GR"}, {"18", "H", "HU"},
                {"19", "HR", "HR"}, {"1A", "I", "IT"}, {"1B", "IRL", "IE"}, {"1C", "IS", "IS"},
                {"1D", "KZ", "KZ"}, {"1E", "L", "LU"}, {"1F", "LT", "LT"}, {"20", "LV", "LV"},
                {"21", "M", "MT"}, {"22", "MC", "MC"}, {"23", "MD", "MD"}, {"24", "MK", "MK"},
                {"25", "N", "NO"}, {"26", "NL", "NL"}, {"27", "P", "PT"}, {"28", "PL", "PL"},
                {"29", "RO", "RO"}, {"2A", "RSM", "SM"}, {"2B", "RUS", "RU"}, {"2C", "S", "SE"},
                {"2D", "SK", "SK"}, {"2E", "SLO", "SI"}, {"2F", "TM", "TM"}, {"30", "TR", "TR"},
                {"31", "UA", "UA"}, {"32", "V", "VA"}, {"33", "YU", null}, {"34", "MNE", "ME"},
                {"35", "SRB", "RS"}, {"36", "UZ", "UZ"}, {"37", "TJ", "TJ"}, {"38", "KG", "KG"},
                {"FD", "EC", null}, {"FE", "EUR", null}, {"FF", "WLD", null},
        };
        for (String[] row : table) {
            int code = Integer.parseInt(row[0], 16);
            KNOWN.put(code, new Nation(code, row[1], row[2]));
        }
    }

    public static Nation of(int code) {
        Nation n = KNOWN.get(code);
        return n != null ? n : new Nation(code, null, null);
    }

    /** 00h: no information available. */
    public boolean isEmpty() {
        return code == 0;
    }

    /** ISO code if there is one, otherwise the tachograph code, otherwise the number in hex. */
    public String display() {
        if (iso2 != null) {
            return iso2;
        }
        return tachoCode != null ? tachoCode : String.format("%02X", code);
    }
}
