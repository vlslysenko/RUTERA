package com.rutera.core.calc;

import com.rutera.common.domain.FuelTransaction;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The EU Weekly Oil Bulletin of 2026-11-02 (test resources wob/, published by the European Commission under CC BY
 * 4.0): parsing of its two workbooks, and the page lookup and download from a stub of the Commission's site.
 */
class EuOilBulletinTest {

    private static HttpServer stub;
    private static byte[] prices;
    private static byte[] duties;

    @BeforeAll
    static void start() throws IOException {
        prices = resource("/wob/prices-with-taxes-2026-11-02.xlsx");
        duties = resource("/wob/duties-and-taxes-2026-11-02.xlsx");
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/data-and-analysis/weekly-oil-bulletin_en", ex -> send(ex, 200, ("<html><a href=\""
                + "/document/download/a_en?filename=Weekly%20Oil%20Bulletin%20Weekly%20prices%20with%20Taxes%20-%20"
                + "2026-09-21.xlsx\">prices</a><a href=\"/document/download/b_en?filename=Weekly%20Oil%20Bulletin%20"
                + "Weekly%20prices%20without%20taxes%20-%202024-02-19.xlsx\">old</a><a href=\"/document/download/c_en"
                + "?filename=Oil_Bulletin_Duties_and_taxes.xlsx\">duties</a></html>").getBytes(StandardCharsets.UTF_8)));
        stub.createContext("/document/download/a_en", ex -> send(ex, 200, prices));
        stub.createContext("/document/download/c_en", ex -> send(ex, 200, duties));
        stub.start();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = EuOilBulletinTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    @Test
    void parsesPricesVatAndExcise() throws Exception {
        List<FuelPriceProvider.PriceQuote> quotes = EuOilBulletin.parse(prices, duties);
        List<FuelPriceProvider.PriceQuote> diesel = quotes.stream()
                .filter(q -> q.product() == FuelTransaction.Product.DIESEL).toList();
        assertThat(diesel).hasSize(27);
        assertThat(quotes.stream().filter(q -> q.product() == FuelTransaction.Product.PETROL)).hasSize(27);
        FuelPriceProvider.PriceQuote pl = diesel.stream().filter(q -> q.country().equals("PL")).findFirst().orElseThrow();
        assertThat(pl.priceDate()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(pl.currency()).isEqualTo("EUR");
        assertThat(pl.gross()).isEqualByComparingTo("1.8120");
        assertThat(pl.vatRate()).isEqualByComparingTo("8");
        assertThat(pl.net()).as("1811.97 / 1.08 / 1000").isEqualByComparingTo("1.6778");
        assertThat(pl.excisePer1000l()).as("in PLN, not given for a EUR price").isNull();
        FuelPriceProvider.PriceQuote de = diesel.stream().filter(q -> q.country().equals("DE")).findFirst().orElseThrow();
        assertThat(de.net()).isEqualByComparingTo("1.9176");
        assertThat(de.vatRate()).isEqualByComparingTo("19");
        assertThat(de.excisePer1000l()).isEqualByComparingTo("330");
        assertThat(de.source()).isEqualTo("EU_WOB");
    }

    @Test
    void findsTheWorkbooksOnThePageAndDownloadsThem() throws Exception {
        EuOilBulletin bulletin = new EuOilBulletin("http://127.0.0.1:" + stub.getAddress().getPort()
                + "/data-and-analysis/weekly-oil-bulletin_en", "RUTERA test");
        assertThat(bulletin.fetch(LocalDate.parse("2026-10-20"))).hasSize(54);
        assertThat(bulletin.fetch(LocalDate.parse("2026-11-03"))).as("nothing newer").isEmpty();
        assertThat(bulletin.kind()).isEqualTo(FuelPriceProvider.Kind.NATIONAL);
        assertThat(bulletin.countries()).hasSize(27).contains("PL", "DE", "SE");
    }
}
