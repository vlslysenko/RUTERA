package com.rutera.core.fuel.read;

import com.rutera.common.domain.FuelProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the header row of a provider export and the column of each {@link Field} by header names: first exact
 * synonyms (folded: lower case, no diacritics, no punctuation), then headers that contain a synonym as whole words,
 * minus words that point elsewhere ("VAT rate" is no VAT amount). Provider presets add their own names; a mapping
 * given by the user (field -> header text) wins over both.
 */
public final class ColumnMapper {

    /** The logical columns of a fuel transaction; {@link #key} is the name in the API and in saved mappings. */
    public enum Field {
        CARD_NUMBER("cardNumber"), PLATE("plate"), DATE("date"), TIME("time"), TRANSACTION_ID("transactionId"),
        STATION("station"), COUNTRY("country"), PRODUCT("product"), QUANTITY("quantity"), UNIT("unit"),
        UNIT_PRICE("unitPrice"), NET("net"), VAT("vat"), GROSS("gross"), CURRENCY("currency"), ODOMETER("odometer");

        public final String key;

        Field(String key) {
            this.key = key;
        }

        public static Field of(String key) {
            for (Field f : values()) {
                if (f.key.equals(key)) {
                    return f;
                }
            }
            return null;
        }
    }

    /** The header row (index into the rows) and the column of each found field. */
    public record Detection(int headerRow, List<String> headers, Map<Field, Integer> columns) {
    }

    private static final Map<Field, List<String>> SYNONYMS = new EnumMap<>(Field.class);
    private static final Map<Field, List<String>> NOT = new EnumMap<>(Field.class);
    private static final Map<FuelProvider, Map<Field, List<String>>> PRESETS = new EnumMap<>(FuelProvider.class);

    static {
        SYNONYMS.put(Field.CARD_NUMBER, List.of("card number", "card no", "card nr", "card", "card id", "pan",
                "kartennummer", "karten nr", "kartennr", "karte", "numer karty", "nr karty", "karta",
                "numero de carte", "carte", "kaart", "kaartnummer", "cislo karty", "numar card"));
        SYNONYMS.put(Field.PLATE, List.of("licence plate", "license plate", "registration", "registration number",
                "vehicle registration", "plate", "plate number", "kennzeichen", "amtl kennzeichen", "kfz kennzeichen",
                "numer rejestracyjny", "nr rejestracyjny", "rejestracja", "immatriculation", "kenteken", "spz",
                "vehicle", "fahrzeug", "pojazd"));
        SYNONYMS.put(Field.DATE, List.of("transaction date", "date", "delivery date", "datum", "lieferdatum",
                "transaktionsdatum", "tankdatum", "data", "data transakcji", "data tankowania", "date de transaction",
                "date transaction", "transactiedatum", "datum transakce", "date time", "datetime"));
        SYNONYMS.put(Field.TIME, List.of("time", "transaction time", "zeit", "uhrzeit", "godzina", "czas", "heure",
                "tijd", "cas"));
        SYNONYMS.put(Field.TRANSACTION_ID, List.of("transaction id", "transaction number", "transaction no",
                "transaction nr", "receipt number", "receipt no", "beleg", "belegnummer", "beleg nr", "belegnr",
                "transaktionsnummer", "transaktions nr", "numer transakcji", "nr transakcji", "id transakcji",
                "nr paragonu", "numero de transaction", "reference", "ref"));
        SYNONYMS.put(Field.STATION, List.of("station", "service station", "station name", "site", "location",
                "tankstelle", "akzeptanzstelle", "stacja", "nazwa stacji", "miejsce", "station service", "lieu",
                "ort", "city", "miejscowosc"));
        SYNONYMS.put(Field.COUNTRY, List.of("country", "country code", "land", "lander", "kraj", "pays", "stat"));
        SYNONYMS.put(Field.PRODUCT, List.of("product", "product name", "product description", "article", "produkt",
                "artikel", "warenart", "ware", "nazwa produktu", "towar", "produkt usluga", "produit", "description",
                "goods", "service"));
        SYNONYMS.put(Field.QUANTITY, List.of("quantity", "qty", "volume", "litres", "liters", "menge", "liter",
                "ilosc", "litry", "quantite", "hoeveelheid", "mnozstvi", "cantitate"));
        SYNONYMS.put(Field.UNIT, List.of("unit", "uom", "einheit", "mengeneinheit", "jednostka", "jm", "unite"));
        SYNONYMS.put(Field.UNIT_PRICE, List.of("unit price", "price per unit", "price per litre", "price l",
                "einzelpreis", "preis je einheit", "literpreis", "cena jednostkowa", "cena za litr",
                "prix unitaire", "price"));
        SYNONYMS.put(Field.NET, List.of("net amount", "net", "net value", "amount excl vat", "amount net",
                "netto", "nettobetrag", "betrag netto", "kwota netto", "wartosc netto", "montant ht", "ht",
                "netto bedrag"));
        SYNONYMS.put(Field.VAT, List.of("vat amount", "vat", "tax amount", "mwst", "mwst betrag", "ust",
                "umsatzsteuer", "kwota vat", "podatek vat", "vat kwota", "tva", "montant tva", "btw"));
        SYNONYMS.put(Field.GROSS, List.of("gross amount", "gross", "gross value", "amount incl vat", "amount gross",
                "total amount", "total", "amount", "brutto", "bruttobetrag", "betrag brutto", "betrag",
                "kwota brutto", "wartosc brutto", "kwota", "montant ttc", "ttc", "montant", "bruto bedrag"));
        SYNONYMS.put(Field.CURRENCY, List.of("currency", "currency code", "curr", "wahrung", "waehrung", "waluta",
                "devise", "valuta", "mena"));
        SYNONYMS.put(Field.ODOMETER, List.of("mileage", "odometer", "km", "km reading", "kilometerstand",
                "km stand", "przebieg", "licznik", "stan licznika", "kilometrage", "kilometerstand km"));

        NOT.put(Field.VAT, List.of("rate", "satz", "stawka", "taux", "percent", "proc", "id", "number", "nummer",
                "nr", "no", "code"));
        NOT.put(Field.GROSS, List.of("net", "netto", "vat", "mwst", "tva", "price", "preis", "cena", "unit",
                "rabatt", "discount", "rabat", "fee"));
        NOT.put(Field.NET, List.of("price", "preis", "cena", "unit"));
        NOT.put(Field.DATE, List.of("valid", "expiry", "invoice", "rechnung", "faktury", "birth", "booking",
                "buchung", "ksiegowania"));
        NOT.put(Field.CARD_NUMBER, List.of("holder", "name", "inhaber", "wlasciciel", "type", "typ"));
        NOT.put(Field.QUANTITY, List.of("price", "preis", "cena"));
        NOT.put(Field.PLATE, List.of("type", "typ", "art", "category"));
        NOT.put(Field.STATION, List.of("number", "nummer", "nr", "id", "code"));

        // Provider presets are filled from real exports as they arrive; until then the generic names apply.
        for (FuelProvider p : FuelProvider.values()) {
            PRESETS.put(p, new EnumMap<>(Field.class));
        }
    }

    private ColumnMapper() {
    }

    /** The provider named in the file name or the first rows; OTHER when none is. */
    public static FuelProvider detectProvider(String fileName, List<List<String>> rows) {
        StringBuilder text = new StringBuilder(" ").append(FuelValues.fold(fileName)).append(' ');
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            text.append(FuelValues.fold(String.join(" ", rows.get(i)))).append(' ');
        }
        String t = text.toString();
        if (t.contains(" dkv ")) {
            return FuelProvider.DKV;
        }
        if (t.contains(" uta ")) {
            return FuelProvider.UTA;
        }
        if (t.contains(" orlen ") || t.contains(" flota ")) {
            return FuelProvider.ORLEN;
        }
        if (t.contains(" shell ")) {
            return FuelProvider.SHELL;
        }
        if (t.contains(" bp ") || t.contains(" bpplus ") || t.contains(" routex ")) {
            return FuelProvider.BP;
        }
        if (t.contains(" e100 ")) {
            return FuelProvider.E100;
        }
        if (t.contains(" as24 ")) {
            return FuelProvider.AS24;
        }
        if (t.contains(" omv ")) {
            return FuelProvider.OMV;
        }
        return FuelProvider.OTHER;
    }

    /**
     * The best header row among the first 15 (most fields found, at least three) and its columns; the explicit
     * mapping (field key -> header text) replaces what was found for its fields. Null when no row looks like a header.
     */
    public static Detection detect(List<List<String>> rows, FuelProvider provider, Map<String, String> explicit) {
        Detection best = null;
        for (int r = 0; r < Math.min(15, rows.size()); r++) {
            List<String> headers = rows.get(r);
            Map<Field, Integer> columns = columns(headers, provider);
            if (explicit != null) {
                apply(headers, explicit, columns);
            }
            if (columns.size() >= 3 && (best == null || columns.size() > best.columns().size())) {
                best = new Detection(r, headers, columns);
            }
        }
        return best;
    }

    private static void apply(List<String> headers, Map<String, String> explicit, Map<Field, Integer> columns) {
        for (Map.Entry<String, String> e : explicit.entrySet()) {
            Field f = Field.of(e.getKey());
            if (f == null) {
                continue;
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                columns.remove(f);
                continue;
            }
            String wanted = FuelValues.fold(e.getValue());
            for (int c = 0; c < headers.size(); c++) {
                if (FuelValues.fold(headers.get(c)).equals(wanted)) {
                    Integer column = c;
                    columns.values().removeIf(column::equals);
                    columns.put(f, c);
                    break;
                }
            }
        }
    }

    static Map<Field, Integer> columns(List<String> headers, FuelProvider provider) {
        List<String> folded = new ArrayList<>();
        for (String h : headers) {
            folded.add(FuelValues.fold(h));
        }
        Map<Field, Integer> result = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Map<Field, List<String>> preset = PRESETS.getOrDefault(provider, Map.of());
        // 1. exact names: the preset first, then the generic synonyms in their order of preference
        for (Field f : Field.values()) {
            List<String> names = new ArrayList<>(preset.getOrDefault(f, List.of()));
            names.addAll(SYNONYMS.get(f));
            outer:
            for (String name : names) {
                String n = FuelValues.fold(name);
                for (int c = 0; c < folded.size(); c++) {
                    if (!used.contains(c) && folded.get(c).equals(n) && !percent(f, headers.get(c))) {
                        result.put(f, c);
                        used.add(c);
                        break outer;
                    }
                }
            }
        }
        // 2. headers that contain a synonym as whole words
        for (Field f : Field.values()) {
            if (result.containsKey(f)) {
                continue;
            }
            for (int c = 0; c < folded.size() && !result.containsKey(f); c++) {
                if (used.contains(c) || folded.get(c).isEmpty() || excluded(f, folded.get(c), headers.get(c))) {
                    continue;
                }
                String h = " " + folded.get(c) + " ";
                for (String name : SYNONYMS.get(f)) {
                    String n = FuelValues.fold(name);
                    if (n.length() >= 3 && h.contains(" " + n + " ")) {
                        result.put(f, c);
                        used.add(c);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** "VAT %" is a rate, never an amount. */
    private static boolean percent(Field f, String raw) {
        return (f == Field.VAT || f == Field.NET || f == Field.GROSS) && raw.contains("%");
    }

    private static boolean excluded(Field f, String folded, String raw) {
        if (percent(f, raw)) {
            return true;
        }
        String h = " " + folded + " ";
        for (String word : NOT.getOrDefault(f, List.of())) {
            if (h.contains(" " + word.toLowerCase(Locale.ROOT) + " ")) {
                return true;
            }
        }
        return false;
    }
}
