package com.rutera.core.fleet.vin;

import com.rutera.common.domain.Vehicle;
import com.rutera.core.fleet.vin.VinDecodeDto.Suggestion;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline VIN decoder for the vehicle form. No external service is called.
 * <p>
 * What a VIN tells reliably: the maker and the country (WMI, characters 1-3, ISO 3780). Character 10 is the model
 * year in North America; European truck makers often leave it out (Scania, DAF and Mercedes-Benz put other codes
 * there), so it is used only for makers known to write it. The check digit (character 9) is mandatory only in North
 * America. The descriptor (characters 4-8) is maker specific; only patterns seen consistently are decoded: the
 * Scania series letter and wheel formula, the Mercedes-Benz model series (Baumuster). A VIN does not carry the Euro
 * class or the engine: these are suggested from a year (model year or the first registration year the user gives)
 * with the presumption rules of the German toll operator and the catalogue {@code fleet/truck-series.csv} derived
 * from the RUTERA research, and always marked as suggestions.
 */
@Component
public class VinDecoder {

    enum Confidence { HIGH, MEDIUM, LOW }

    /** Whether a maker writes the model year into character 10. */
    enum YearCode { YES, NO, UNKNOWN }

    enum Category { TRUCK, VAN, TRAILER, MIXED }

    record Maker(String make, Category category, YearCode yearCode) {
    }

    /** A generation of a truck series from {@code fleet/truck-series.csv}; {@code to} null = still built. */
    record Generation(String make, String series, String name, int from, Integer to, List<String> fuels,
                      List<String> euro, List<String> engines) {

        boolean covers(Integer year) {
            return year == null || (from <= year && (to == null || year <= to));
        }
    }

    private record Model(List<String> series, Vehicle.Kind kind, Confidence kindConfidence) {
    }

    private record Range(char first, char from, char to, String country) {
    }

    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern WHEELS = Pattern.compile("([468])X([24])");
    private static final String YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789";
    private static final String TRANSLITERATION = "0123456789.ABCDEFGH..JKLMN.P.R..STUVWXYZ";
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
    /** Order of the second character in the ISO 3780 country ranges. */
    private static final String SECOND = "ABCDEFGHJKLMNPRSTUVWXYZ1234567890";

    private static final String MB = "Mercedes-Benz";

    private static final Map<String, Maker> MAKERS = Map.ofEntries(
            Map.entry("XLR", new Maker("DAF", Category.TRUCK, YearCode.NO)),
            Map.entry("YS2", new Maker("Scania", Category.TRUCK, YearCode.NO)),
            Map.entry("XLE", new Maker("Scania", Category.TRUCK, YearCode.NO)),
            Map.entry("9BS", new Maker("Scania", Category.TRUCK, YearCode.NO)),
            Map.entry("YV2", new Maker("Volvo", Category.TRUCK, YearCode.YES)),
            Map.entry("YV5", new Maker("Volvo", Category.TRUCK, YearCode.YES)),
            Map.entry("4V4", new Maker("Volvo", Category.TRUCK, YearCode.YES)),
            Map.entry("WMA", new Maker("MAN", Category.TRUCK, YearCode.UNKNOWN)),
            Map.entry("VF6", new Maker("Renault Trucks", Category.TRUCK, YearCode.UNKNOWN)),
            Map.entry("VG8", new Maker("Renault Trucks", Category.TRUCK, YearCode.UNKNOWN)),
            Map.entry("ZCF", new Maker("Iveco", Category.MIXED, YearCode.UNKNOWN)),
            Map.entry("WJM", new Maker("Iveco", Category.TRUCK, YearCode.UNKNOWN)),
            Map.entry("WDB", new Maker(MB, Category.MIXED, YearCode.NO)),
            Map.entry("W1T", new Maker(MB, Category.TRUCK, YearCode.NO)),
            Map.entry("WDF", new Maker(MB, Category.VAN, YearCode.NO)),
            Map.entry("W1V", new Maker(MB, Category.VAN, YearCode.NO)),
            Map.entry("WV1", new Maker("Volkswagen", Category.VAN, YearCode.YES)),
            Map.entry("WV2", new Maker("Volkswagen", Category.VAN, YearCode.YES)),
            Map.entry("VF1", new Maker("Renault", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("VF3", new Maker("Peugeot", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("VR3", new Maker("Peugeot", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("VF7", new Maker("Citroen", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("VR7", new Maker("Citroen", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("ZFA", new Maker("Fiat", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("WF0", new Maker("Ford", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("NM0", new Maker("Ford", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("W0L", new Maker("Opel", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("W0V", new Maker("Opel", Category.VAN, YearCode.UNKNOWN)),
            Map.entry("WSM", new Maker("Schmitz Cargobull", Category.TRAILER, YearCode.UNKNOWN)),
            Map.entry("WKE", new Maker("Krone", Category.TRAILER, YearCode.UNKNOWN)));

    /** Mercedes-Benz model series (characters 4-6) seen in truck and van VINs. */
    private static final Map<String, Model> MB_SERIES = Map.ofEntries(
            Map.entry("930", new Model(List.of("Actros"), null, null)),
            Map.entry("932", new Model(List.of("Actros"), null, null)),
            Map.entry("933", new Model(List.of("Actros"), null, null)),
            Map.entry("934", new Model(List.of("Actros"), null, null)),
            Map.entry("963", new Model(List.of("Actros", "Arocs", "Antos"), Vehicle.Kind.TRACTOR, Confidence.LOW)),
            Map.entry("964", new Model(List.of("Actros", "Arocs", "Antos"), Vehicle.Kind.TRUCK, Confidence.LOW)),
            Map.entry("967", new Model(List.of("Atego"), Vehicle.Kind.TRUCK, Confidence.LOW)),
            Map.entry("970", new Model(List.of("Atego"), Vehicle.Kind.TRUCK, Confidence.LOW)),
            Map.entry("901", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("902", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("903", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("904", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("905", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("906", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("907", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("910", new Model(List.of("Sprinter"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("639", new Model(List.of("Vito"), Vehicle.Kind.VAN, Confidence.MEDIUM)),
            Map.entry("447", new Model(List.of("Vito"), Vehicle.Kind.VAN, Confidence.MEDIUM)));

    /** Scania series letter (character 4). */
    private static final String SCANIA_SERIES = "PGRS";

    /** ISO 3780 country ranges (first character, second character from-to), the common ones only. */
    private static final List<Range> COUNTRIES = List.of(
            new Range('J', 'A', '0', "JP"), new Range('K', 'L', 'R', "KR"), new Range('L', 'A', '0', "CN"),
            new Range('N', 'L', 'R', "TR"),
            new Range('S', 'A', 'M', "GB"), new Range('S', 'N', 'T', "DE"), new Range('S', 'U', 'Z', "PL"),
            new Range('T', 'A', 'H', "CH"), new Range('T', 'J', 'P', "CZ"), new Range('T', 'R', 'V', "HU"),
            new Range('T', 'W', '1', "PT"),
            new Range('U', 'H', 'M', "DK"), new Range('U', 'N', 'T', "IE"), new Range('U', 'U', 'Z', "RO"),
            new Range('U', '5', '7', "SK"),
            new Range('V', 'A', 'E', "AT"), new Range('V', 'F', 'R', "FR"), new Range('V', 'S', 'W', "ES"),
            new Range('W', 'A', '0', "DE"),
            new Range('X', 'A', 'E', "BG"), new Range('X', 'F', 'K', "GR"), new Range('X', 'L', 'R', "NL"),
            new Range('X', 'X', '2', "LU"), new Range('X', '3', '0', "RU"),
            new Range('Y', 'A', 'E', "BE"), new Range('Y', 'F', 'K', "FI"), new Range('Y', 'S', 'W', "SE"),
            new Range('Y', 'X', '2', "NO"), new Range('Y', '3', '5', "BY"), new Range('Y', '6', '0', "UA"),
            new Range('Z', 'A', 'R', "IT"),
            new Range('1', 'A', '0', "US"), new Range('4', 'A', '0', "US"), new Range('5', 'A', '0', "US"),
            new Range('2', 'A', '0', "CA"), new Range('3', 'A', 'W', "MX"),
            new Range('6', 'A', 'W', "AU"), new Range('8', 'A', 'E', "AR"), new Range('9', 'A', 'E', "BR"));

    private static final List<String> EURO = Arrays.stream(Vehicle.EuroClass.values()).map(Enum::name).toList();

    private final List<Generation> generations;
    private final Clock clock;

    public VinDecoder() {
        this(Clock.systemDefaultZone());
    }

    VinDecoder(Clock clock) {
        this.clock = clock;
        this.generations = load("fleet/truck-series.csv");
    }

    /**
     * Decodes a VIN.
     *
     * @param raw               the VIN as typed (spaces and hyphens are ignored)
     * @param registrationYear  year of first registration, optional; better than the model year for the Euro class
     */
    public VinDecodeDto decode(String raw, Integer registrationYear) {
        String vin = raw == null ? "" : raw.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (vin.length() > 32) {
            vin = vin.substring(0, 32);
        }
        if (vin.length() != 17) {
            return VinDecodeDto.invalid(vin, "INVALID_LENGTH");
        }
        if (!VIN.matcher(vin).matches()) {
            return VinDecodeDto.invalid(vin, "INVALID_CHARS");
        }
        int thisYear = Year.now(clock).getValue();
        String wmi = vin.substring(0, 3);
        Maker maker = MAKERS.get(wmi);
        boolean northAmerica = vin.charAt(0) >= '1' && vin.charAt(0) <= '5';

        // Model year (character 10)
        Integer modelYear = null;
        Confidence yearConfidence = null;
        YearCode yearCode = northAmerica ? YearCode.YES : maker == null ? YearCode.UNKNOWN : maker.yearCode();
        if (yearCode != YearCode.NO) {
            modelYear = modelYear(vin.charAt(9), thisYear + 1);
            if (modelYear != null) {
                yearConfidence = northAmerica ? Confidence.HIGH
                        : yearCode == YearCode.YES ? Confidence.MEDIUM : Confidence.LOW;
            }
        }
        Integer year = null;
        String yearSource = null;
        if (registrationYear != null && registrationYear >= 1980 && registrationYear <= thisYear + 1) {
            year = registrationYear;
            yearSource = "REGISTRATION";
        } else if (modelYear != null && yearConfidence != Confidence.LOW) {
            year = modelYear;
            yearSource = "MODEL_YEAR";
        }

        List<Suggestion> suggestions = new ArrayList<>();
        String wheels = null;
        List<String> engines = List.of();
        if (maker != null) {
            suggestions.add(new Suggestion("make", maker.make(), List.of(), Confidence.HIGH.name(), "WMI"));

            // Model and kind from the descriptor, then from the catalogue
            List<String> series = List.of();
            String seriesBasis = "VDS";
            Vehicle.Kind kind = null;
            Confidence kindConfidence = null;
            String kindBasis = "VDS";
            if ("Scania".equals(maker.make()) && SCANIA_SERIES.indexOf(vin.charAt(3)) >= 0) {
                series = List.of(String.valueOf(vin.charAt(3)));
            } else if (MB.equals(maker.make())) {
                Model m = MB_SERIES.get(vin.substring(3, 6));
                if (m != null) {
                    series = m.series();
                    kind = m.kind();
                    kindConfidence = m.kindConfidence();
                }
            }
            List<Generation> makeGenerations = generations.stream().filter(g -> g.make().equals(maker.make())).toList();
            if (!series.isEmpty() && year != null) {
                Integer y = year;
                List<String> built = series.stream()
                        .filter(s -> makeGenerations.stream().anyMatch(g -> g.series().equals(s) && g.covers(y)))
                        .toList();
                if (!built.isEmpty()) {
                    series = built;
                }
            }
            Confidence seriesConfidence = series.size() == 1 ? Confidence.MEDIUM : Confidence.LOW;
            boolean fromVds = !series.isEmpty();
            if (series.isEmpty() && year != null && maker.category() == Category.TRUCK && !makeGenerations.isEmpty()) {
                Integer y = year;
                series = makeGenerations.stream().filter(g -> g.covers(y)).map(Generation::series).distinct().sorted()
                        .toList();
                seriesBasis = "MODELS";
                seriesConfidence = Confidence.LOW;
            }
            if (!series.isEmpty()) {
                suggestions.add(new Suggestion("model", series.size() == 1 ? series.getFirst() : null,
                        series.size() == 1 ? List.of() : series, seriesConfidence.name(), seriesBasis));
            }
            if (kind == null && maker.category() == Category.TRAILER) {
                kind = Vehicle.Kind.SEMI_TRAILER;
                kindConfidence = Confidence.LOW;
                kindBasis = "WMI";
            } else if (kind == null && maker.category() == Category.VAN) {
                kind = Vehicle.Kind.VAN;
                kindConfidence = Confidence.LOW;
                kindBasis = "WMI";
            }
            if (kind != null) {
                List<String> options = kind == Vehicle.Kind.SEMI_TRAILER
                        ? List.of(Vehicle.Kind.SEMI_TRAILER.name(), Vehicle.Kind.TRAILER.name()) : List.of();
                suggestions.add(new Suggestion("kind", kind.name(), options, kindConfidence.name(), kindBasis));
            }

            // Wheel formula (Scania writes it in characters 5-8)
            if ("Scania".equals(maker.make())) {
                Matcher w = WHEELS.matcher(vin.substring(4, 9));
                if (w.find()) {
                    wheels = w.group();
                    int axles = (w.group(1).charAt(0) - '0') / 2;
                    suggestions.add(new Suggestion("axles", String.valueOf(axles), List.of(),
                            Confidence.MEDIUM.name(), "WHEELS"));
                }
            }

            // Fuel, Euro class and engines from the catalogue (heavy trucks only)
            boolean heavy = maker.category() == Category.TRUCK
                    || (maker.category() == Category.MIXED && kind != Vehicle.Kind.VAN && fromVds);
            if (heavy && !makeGenerations.isEmpty()) {
                Integer y = year;
                List<String> s = series;
                List<Generation> matching = makeGenerations.stream()
                        .filter(g -> (s.isEmpty() || s.contains(g.series())) && g.covers(y))
                        .toList();
                if (!matching.isEmpty()) {
                    Set<String> fuels = union(matching, Generation::fuels);
                    if (!fuels.isEmpty()) {
                        String value = fuels.contains("DIESEL") ? "DIESEL" : fuels.iterator().next();
                        boolean only = fuels.size() == 1;
                        suggestions.add(new Suggestion("fuelType", value, only ? List.of() : sortedByEnum(fuels),
                                (only && !s.isEmpty() && y != null ? Confidence.MEDIUM : Confidence.LOW).name(),
                                "MODELS"));
                    }
                    if (y != null) {
                        Suggestion euro = euro(y, union(matching, Generation::euro), fuels);
                        if (euro != null) {
                            suggestions.add(euro);
                        }
                    }
                    if (s.size() == 1 && y != null) {
                        engines = List.copyOf(union(matching, Generation::engines));
                    }
                }
            }
        }

        String checkDigit = checkDigit(vin) == vin.charAt(8) ? "VALID" : "INVALID";
        return new VinDecodeDto(vin, "OK", wmi, maker == null ? null : maker.make(), country(vin), checkDigit,
                northAmerica, modelYear, yearConfidence == null ? null : yearConfidence.name(),
                yearCode == YearCode.NO, year, yearSource, wheels, List.copyOf(suggestions), engines);
    }

    /**
     * Euro class presumed for a first registration in {@code year} (Toll Collect rules, research file
     * euro-class-by-first-registration.csv), widened by the classes the catalogue knows for the model.
     */
    static Suggestion euro(int year, Set<String> catalogue, Set<String> fuels) {
        if (fuels.size() == 1 && fuels.contains("ELECTRIC")) {
            return null;
        }
        List<String> presumed = presumed(year);
        if (presumed.isEmpty()) {
            return null;
        }
        Set<String> options = new TreeSet<>(presumed);
        options.addAll(catalogue);
        boolean certain = options.size() == 1 && year != 2001;
        // Toll Collect presumes by the date: in a year of change the months before October keep the older class.
        String value = presumed.getFirst();
        return new Suggestion("euroClass", value, options.size() == 1 ? List.of() : List.copyOf(options),
                (certain ? Confidence.MEDIUM : Confidence.LOW).name(), "YEAR");
    }

    /** Euro classes in force for first registrations during {@code year}, oldest first; empty before Euro 3. */
    static List<String> presumed(int year) {
        if (year <= 2000) {
            return List.of();
        }
        if (year <= 2005) {
            return List.of("EURO_3");
        }
        if (year == 2006) {
            return List.of("EURO_3", "EURO_4");
        }
        if (year <= 2008) {
            return List.of("EURO_4");
        }
        if (year == 2009) {
            return List.of("EURO_4", "EURO_5");
        }
        if (year <= 2013) {
            return List.of("EURO_5");
        }
        return List.of("EURO_6");
    }

    /** Model year of a character-10 code: the latest year of the 30-year cycle that is not after {@code maxYear}. */
    static Integer modelYear(char code, int maxYear) {
        int i = YEAR_CODES.indexOf(code);
        if (i < 0) {
            return null;
        }
        int year = 1980 + i;
        while (year + 30 <= maxYear) {
            year += 30;
        }
        return year;
    }

    /** ISO 3779 / FMVSS 115 check digit of a 17-character VIN. */
    static char checkDigit(String vin) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (TRANSLITERATION.indexOf(vin.charAt(i)) % 10) * WEIGHTS[i];
        }
        int rest = sum % 11;
        return rest == 10 ? 'X' : (char) ('0' + rest);
    }

    static String country(String vin) {
        char first = vin.charAt(0);
        int second = SECOND.indexOf(vin.charAt(1));
        for (Range r : COUNTRIES) {
            if (r.first() == first && second >= SECOND.indexOf(r.from()) && second <= SECOND.indexOf(r.to())) {
                return r.country();
            }
        }
        return null;
    }

    private static Set<String> union(List<Generation> list, Function<Generation, List<String>> f) {
        Set<String> out = new LinkedHashSet<>();
        list.forEach(g -> out.addAll(f.apply(g)));
        return out;
    }

    private static List<String> sortedByEnum(Collection<String> fuels) {
        List<String> order = Arrays.stream(Vehicle.FuelType.values()).map(Enum::name).toList();
        return fuels.stream().sorted((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b))).toList();
    }

    List<Generation> generations() {
        return generations;
    }

    private static List<Generation> load(String resource) {
        InputStream in = VinDecoder.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + resource);
        }
        List<Generation> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            boolean header = true;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] c = line.split(";", -1);
                out.add(new Generation(c[0], c[1], c[2], Integer.parseInt(c[3]),
                        c[4].isEmpty() ? null : Integer.valueOf(c[4]), list(c[6]),
                        list(c[7]).stream().filter(EURO::contains).toList(), list(c[8])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    private static List<String> list(String cell) {
        return cell.isEmpty() ? List.of() : List.of(cell.split("\\|"));
    }
}
