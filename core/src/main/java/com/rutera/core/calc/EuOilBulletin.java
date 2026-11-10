package com.rutera.core.calc;

import com.rutera.common.domain.FuelTransaction;
import com.rutera.core.fuel.read.TableReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The European Commission's Weekly Oil Bulletin (CC BY 4.0) as a {@link FuelPriceProvider}: the national average
 * prices with taxes of Monday, published on Thursday, for the 27 EU countries. Its page
 * ({@code rutera.fuel.bulletin.url}) links the current "prices with taxes" workbook (EUR per 1000 L, also for the
 * countries outside the euro) and the "duties and taxes" workbook (VAT rate, excise in national currency); the
 * quotes are per litre in EUR: gross, net = gross without VAT, the VAT rate, the excise for euro countries.
 * Products: DIESEL (automotive gas oil) and PETROL (Euro-super 95). Off with {@code rutera.fuel.bulletin.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "rutera.fuel.bulletin.enabled", havingValue = "true", matchIfMissing = true)
public class EuOilBulletin implements FuelPriceProvider {

    static final String NAME = "EU_WOB";
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("austria", "AT"), Map.entry("belgium", "BE"), Map.entry("bulgaria", "BG"),
            Map.entry("croatia", "HR"), Map.entry("cyprus", "CY"), Map.entry("czechia", "CZ"),
            Map.entry("denmark", "DK"), Map.entry("estonia", "EE"), Map.entry("finland", "FI"),
            Map.entry("france", "FR"), Map.entry("germany", "DE"), Map.entry("greece", "GR"),
            Map.entry("hungary", "HU"), Map.entry("ireland", "IE"), Map.entry("italy", "IT"),
            Map.entry("latvia", "LV"), Map.entry("lithuania", "LT"), Map.entry("luxembourg", "LU"),
            Map.entry("malta", "MT"), Map.entry("netherlands", "NL"), Map.entry("poland", "PL"),
            Map.entry("portugal", "PT"), Map.entry("romania", "RO"), Map.entry("slovakia", "SK"),
            Map.entry("slovenia", "SI"), Map.entry("spain", "ES"), Map.entry("sweden", "SE"));
    /** Countries whose excise the bulletin gives in another currency than EUR. */
    private static final Set<String> NOT_EURO = Set.of("CZ", "DK", "HU", "PL", "RO", "SE");

    private final String pageUrl;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public EuOilBulletin(@Value("${rutera.fuel.bulletin.url:https://energy.ec.europa.eu/data-and-analysis/weekly-oil-bulletin_en}")
                         String pageUrl,
                         @Value("${rutera.geocoding.user-agent:RUTERA}") String userAgent) {
        this.pageUrl = pageUrl;
        this.userAgent = userAgent;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> countries() {
        return Set.copyOf(CODES.values());
    }

    @Override
    public Kind kind() {
        return Kind.NATIONAL;
    }

    @Override
    public List<PriceQuote> fetch(LocalDate since) throws Exception {
        URI page = URI.create(pageUrl);
        String html = new String(get(page), java.nio.charset.StandardCharsets.UTF_8);
        URI prices = null;
        URI duties = null;
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            String lower = java.net.URLDecoder.decode(href, java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (!lower.contains(".xlsx")) {
                continue;
            }
            if (prices == null && lower.contains("prices with taxes")) {
                prices = page.resolve(href);
            } else if (duties == null && lower.contains("duties") && lower.contains("taxes")) {
                duties = page.resolve(href);
            }
        }
        if (prices == null || duties == null) {
            throw new IOException("the bulletin page links no prices or duties workbook");
        }
        List<PriceQuote> quotes = parse(get(prices), get(duties));
        return quotes.stream().filter(q -> !q.priceDate().isBefore(since)).toList();
    }

    /** The quotes of a "prices with taxes" workbook and a "duties and taxes" workbook. */
    static List<PriceQuote> parse(byte[] pricesXlsx, byte[] dutiesXlsx) throws TableReader.UnreadableException {
        List<List<String>> prices = TableReader.readSheet(pricesXlsx, 0, 100);
        if (prices.size() < 3) {
            throw new TableReader.UnreadableException("the prices workbook has no rows");
        }
        int diesel = column(prices.get(0), "automotive gas oil");
        int petrol = column(prices.get(0), "euro-super 95");
        LocalDate day = date(prices.get(1).getFirst());
        List<List<String>> vatRows = TableReader.readSheet(dutiesXlsx, 0, 100);
        List<List<String>> exciseRows = TableReader.readSheet(dutiesXlsx, 1, 100);
        Map<String, BigDecimal[]> vat = byCode(vatRows);
        Map<String, BigDecimal[]> excise = byCode(exciseRows);
        int vatDiesel = headerColumn(vatRows, "automotive gas oil");
        int vatPetrol = headerColumn(vatRows, "euro-super 95");
        int exciseDiesel = headerColumn(exciseRows, "automotive gas oil");
        int excisePetrol = headerColumn(exciseRows, "euro-super 95");
        List<PriceQuote> quotes = new ArrayList<>();
        for (List<String> row : prices.subList(2, prices.size())) {
            String code = CODES.get(row.getFirst().strip().toLowerCase(Locale.ROOT));
            if (code == null) {
                continue;
            }
            add(quotes, code, FuelTransaction.Product.DIESEL, cell(row, diesel), vat.get(code), vatDiesel,
                    excise.get(code), exciseDiesel, day);
            add(quotes, code, FuelTransaction.Product.PETROL, cell(row, petrol), vat.get(code), vatPetrol,
                    excise.get(code), excisePetrol, day);
        }
        return quotes;
    }

    private static void add(List<PriceQuote> quotes, String code, FuelTransaction.Product product,
                            BigDecimal per1000, BigDecimal[] vatRow, int vatColumn, BigDecimal[] exciseRow,
                            int exciseColumn, LocalDate day) {
        BigDecimal rate = vatRow == null || vatColumn >= vatRow.length ? null : vatRow[vatColumn];
        if (per1000 == null || per1000.signum() <= 0 || rate == null) {
            return;
        }
        BigDecimal gross = per1000.divide(THOUSAND, 4, RoundingMode.HALF_UP);
        BigDecimal net = per1000.multiply(HUNDRED).divide(HUNDRED.add(rate), 6, RoundingMode.HALF_UP)
                .divide(THOUSAND, 4, RoundingMode.HALF_UP);
        BigDecimal excise = NOT_EURO.contains(code) || exciseRow == null || exciseColumn >= exciseRow.length ? null
                : exciseRow[exciseColumn];
        quotes.add(new PriceQuote(code, product, net, gross, rate, excise, "EUR", null, null, null, day, NAME,
                Quality.REFERENCE, null, null));
    }

    /** Duties rows by country code ("AT_"): the numbers of the row by column (null where empty). */
    private static Map<String, BigDecimal[]> byCode(List<List<String>> rows) {
        Map<String, BigDecimal[]> out = new HashMap<>();
        for (List<String> row : rows) {
            String first = row.isEmpty() ? "" : row.getFirst().strip();
            if (first.length() == 3 && first.endsWith("_")) {
                BigDecimal[] values = new BigDecimal[row.size()];
                for (int i = 1; i < row.size(); i++) {
                    values[i] = number(row.get(i));
                }
                out.put(first.substring(0, 2), values);
            }
        }
        return out;
    }

    private static int headerColumn(List<List<String>> rows, String text) {
        for (List<String> row : rows) {
            int c = column(row, text);
            if (c >= 0) {
                return c;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int column(List<String> header, String text) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static BigDecimal cell(List<String> row, int column) {
        return column < 0 || column >= row.size() ? null : number(row.get(column));
    }

    private static BigDecimal number(String text) {
        try {
            return text == null || text.isBlank() ? null : new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The bulletin's day: an Excel serial (46328) or a formatted date ("2026-11-02 00:00:00"). */
    static LocalDate date(String text) throws TableReader.UnreadableException {
        String t = text == null ? "" : text.strip();
        try {
            if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return LocalDate.parse(t.substring(0, 10));
            }
            return LocalDate.of(1899, 12, 30).plusDays(new BigDecimal(t).longValue());
        } catch (RuntimeException e) {
            throw new TableReader.UnreadableException("no bulletin date: " + t);
        }
    }

    private byte[] get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) {
                throw new IOException("the bulletin answered " + res.statusCode() + " for " + uri.getPath());
            }
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("the bulletin file is too large: " + uri.getPath());
            }
            return data;
        }
    }
}
