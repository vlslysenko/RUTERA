package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Fuel card exports: preview, import with header detection, duplicates, cards created on the way, matching of
 * vehicle, trip and driver, manual entries and assignments, undoing an import. Firma zone Europe/Warsaw.
 */
class FuelTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    private static final String ORLEN_CSV = """
            Orlen Flota - zestawienie transakcji
            Numer karty;Nr rejestracyjny;Data;Godzina;Stacja;Kraj;Produkt;Ilość;Cena jednostkowa;Kwota netto;VAT;\
            Kwota brutto;Waluta;Przebieg;Nr transakcji
            7001 2345 6789 0001;WX1234A;31.10.2026;14:05;Poznań A2;PL;ON;400,50;5,20;2082,60;479,00;2561,60;PLN;\
            250 100;T-1001
            7001 2345 6789 0001;WX1234A;31.10.2026;14:09;Poznań A2;PL;AdBlue;20;3,00;60,00;13,80;73,80;PLN;;T-1002
            7001 2345 6789 0001;WX1234A;31.13.2026;10:00;Poznań A2;PL;ON;10;5;50;11,5;61,5;PLN;;T-1003
            """;

    private static final String DKV_CSV = """
            Card number,Date,Product,Quantity,Gross amount,Currency,Country,Transaction ID
            "7034 0512 0000 9999",2026-10-30 22:10,Diesel,300.00,450.30,EUR,DEU,ABC-1
            """;

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Fuel Test " + n);
        firma.setShortCode("FUEL" + n);
        firma.setCountry("PL");
        firma.setTimezone("Europe/Warsaw");
        firma.setBaseCurrency("PLN");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-fuel" + n + "@test.local", "FuelOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "FuelOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private MvcResult upload(Client c, String path, String name, byte[] bytes, Map<String, String> params)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(path);
        request.file(new MockMultipartFile("file", name, "text/csv", bytes));
        params.forEach(request::param);
        request.accept(MediaType.APPLICATION_JSON).session(c.session)
                .cookie(new Cookie(CSRF_COOKIE, c.csrf)).header(CSRF_HEADER, c.csrf);
        return mvc.perform(request).andReturn();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anExportIsPreviewedImportedOnceAndMatched() throws Exception {
        Client c = newFirmaOwner();
        long vehicleId = id(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "WX 1234A")));
        long driverId = id(c.post("/app/drivers", Map.of("firstName", "Piotr", "lastName", "Tankowy")));
        Map<String, Object> trip = new HashMap<>();
        trip.put("vehicleId", vehicleId);
        trip.put("mainDriverId", driverId);
        trip.put("plannedStart", "2026-10-31T08:00:00Z");
        trip.put("plannedEnd", "2026-11-01T18:00:00Z");
        trip.put("orderIds", List.of());
        trip.put("stops", List.of());
        long tripId = id(c.post("/app/trips", trip));

        JsonNode preview = body(upload(c, "/app/fuel/imports/preview", "orlen-pazdziernik.csv", utf8(ORLEN_CSV),
                Map.of()));
        assertThat(preview.get("provider").asString()).isEqualTo("ORLEN");
        assertThat(preview.get("headerRow").asInt()).isEqualTo(2);
        assertThat(preview.get("mapping").get("date").asString()).isEqualTo("Data");
        assertThat(preview.get("mapping").get("odometer").asString()).isEqualTo("Przebieg");
        assertThat(preview.get("mapping").get("unit").isNull()).isTrue();
        assertThat(preview.get("missing")).isEmpty();
        assertThat(preview.get("dataRows").asInt()).isEqualTo(3);
        JsonNode first = preview.get("rows").get(0);
        assertThat(first.get("row").asInt()).isEqualTo(3);
        assertThat(first.get("values").get("product").asString()).isEqualTo("DIESEL");
        assertThat(first.get("values").get("cardNumber").asString()).isEqualTo("7001 **** 0001");
        assertThat(first.get("values").get("at").asString()).startsWith("2026-10-31T13:05");
        assertThat(first.get("values").get("gross").asDouble()).isEqualTo(2561.6);
        assertThat(preview.get("rows").get(2).get("errors").get("date").asString())
                .isEqualTo("validation.invalid_format");

        MvcResult imported = upload(c, "/app/fuel/imports", "orlen-pazdziernik.csv", utf8(ORLEN_CSV), Map.of());
        long importId = id(imported);
        JsonNode imp = body(imported);
        assertThat(imp.get("provider").asString()).isEqualTo("ORLEN");
        assertThat(imp.get("rowsTotal").asInt()).isEqualTo(3);
        assertThat(imp.get("rowsNew").asInt()).isEqualTo(2);
        assertThat(imp.get("rowsError").asInt()).isEqualTo(1);
        assertThat(imp.get("errors").get(0).get("row").asInt()).isEqualTo(5);
        assertThat(imp.get("errors").get(0).get("field").asString()).isEqualTo("date");
        assertThat(imp.get("periodFrom").asString()).isEqualTo("2026-10-31");

        JsonNode page = body(c.get("/app/fuel/transactions?from=2026-10-01&to=2026-11-30"));
        assertThat(page.get("total").asLong()).isEqualTo(2);
        JsonNode diesel = page.get("items").get(1);
        assertThat(diesel.get("product").asString()).isEqualTo("DIESEL");
        assertThat(diesel.get("unit").asString()).isEqualTo("L");
        assertThat(diesel.get("odometerKm").asInt()).isEqualTo(250100);
        assertThat(diesel.get("vehicle").get("id").asLong()).isEqualTo(vehicleId);
        assertThat(diesel.get("trip").get("id").asLong()).isEqualTo(tripId);
        assertThat(diesel.get("driver").get("id").asLong()).isEqualTo(driverId);
        assertThat(diesel.get("match").asString()).isEqualTo("CARD");
        assertThat(diesel.get("card").get("masked").asString()).isEqualTo("7001 **** 0001");
        assertThat(page.get("totals").get(0).get("gross").asDouble()).isEqualTo(2635.4);
        assertThat(page.get("totals").get(0).get("vat").asDouble()).isEqualTo(492.8);
        assertThat(page.get("quantities")).hasSize(2);

        JsonNode again = body(upload(c, "/app/fuel/imports", "orlen-pazdziernik.csv", utf8(ORLEN_CSV), Map.of()));
        assertThat(again.get("rowsNew").asInt()).isZero();
        assertThat(again.get("rowsDup").asInt()).isEqualTo(2);
        assertThat(body(c.get("/app/fuel/transactions")).get("total").asLong()).isEqualTo(2);

        JsonNode cards = body(c.get("/app/fuel/cards"));
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("provider").asString()).isEqualTo("ORLEN");
        assertThat(cards.get(0).get("vehicle").get("name").asString()).isEqualTo("WX 1234A");
        assertThat(body(c.get("/app/fuel/mappings/ORLEN")).get("columns").get("date").asString()).isEqualTo("Data");

        JsonNode dkv = body(upload(c, "/app/fuel/imports", "export.csv", utf8(DKV_CSV),
                Map.of("provider", "DKV", "saveMapping", "false")));
        assertThat(dkv.get("rowsNew").asInt()).isEqualTo(1);
        assertThat(body(c.get("/app/fuel/mappings/DKV")).get("columns")).isEmpty();
        JsonNode unmatched = body(c.get("/app/fuel/transactions?unmatched=true"));
        assertThat(unmatched.get("total").asLong()).isEqualTo(1);
        JsonNode open = unmatched.get("items").get(0);
        assertThat(open.get("at").asString()).startsWith("2026-10-30T21:10");
        assertThat(open.get("country").asString()).isEqualTo("DE");
        assertThat(open.get("match").asString()).isEqualTo("NONE");

        long dkvCard = open.get("card").get("id").asLong();
        Map<String, Object> card = new HashMap<>();
        card.put("provider", "DKV");
        card.put("vehicleId", vehicleId);
        assertThat(c.put("/app/fuel/cards/" + dkvCard, card).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(c.get("/app/fuel/transactions?unmatched=true")).get("total").asLong()).isZero();
        card.put("cardNumber", "7001 2345 6789 0001");
        card.put("provider", "ORLEN");
        MvcResult taken = c.post("/app/fuel/cards", card);
        assertThat(taken.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(taken).get("details").get("cardNumber").asString()).isEqualTo("fuel.card.number_taken");

        Map<String, Object> manual = new HashMap<>();
        manual.put("at", "2026-10-31T15:00:00Z");
        manual.put("vehicleId", vehicleId);
        manual.put("product", "DIESEL");
        manual.put("quantity", 50);
        manual.put("gross", 260);
        manual.put("currency", "pln");
        MvcResult created = c.post("/app/fuel/transactions", manual);
        long manualId = id(created);
        assertThat(body(created).get("source").asString()).isEqualTo("MANUAL");
        assertThat(body(created).get("trip").get("id").asLong()).isEqualTo(tripId);
        assertThat(body(created).get("unit").asString()).isEqualTo("L");
        manual.put("gross", 270);
        assertThat(body(c.put("/app/fuel/transactions/" + manualId, manual)).get("gross").asDouble())
                .isEqualTo(270.0);
        long importedId = diesel.get("id").asLong();
        assertThat(c.put("/app/fuel/transactions/" + importedId, manual).getResponse().getStatus())
                .as("imported values stay").isEqualTo(409);
        Map<String, Object> assign = new HashMap<>();
        assign.put("vehicleId", vehicleId);
        assign.put("driverId", null);
        assign.put("tripId", null);
        assign.put("notes", "corrected by hand");
        JsonNode assigned = body(c.patch("/app/fuel/transactions/" + importedId, assign));
        assertThat(assigned.get("match").asString()).isEqualTo("MANUAL");
        assertThat(assigned.get("trip").isNull()).isTrue();

        assertThat(c.delete("/app/fuel/imports/" + importId).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get("/app/fuel/transactions?importId=" + importId)).get("total").asLong()).isZero();
        assertThat(body(c.get("/app/fuel/transactions")).get("total").asLong()).as("DKV row and the manual one")
                .isEqualTo(2);
        assertThat(body(c.get("/app/fuel/imports")).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void unreadableFilesAndMissingColumnsAreRejected() throws Exception {
        Client c = newFirmaOwner();
        MvcResult missing = upload(c, "/app/fuel/imports", "x.csv", utf8("Foo;Bar;Baz\n1;2;3\n"), Map.of());
        assertThat(missing.getResponse().getStatus()).isEqualTo(422);
        JsonNode details = body(missing).get("details");
        assertThat(details.get("file").asString()).isEqualTo("fuel.import.columns_missing");
        assertThat(details.get("columns.date").asString()).isEqualTo("validation.required");

        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        MvcResult old = upload(c, "/app/fuel/imports/preview", "old.xls", xls, Map.of());
        assertThat(old.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(old).get("details").get("file").asString()).isEqualTo("fuel.import.unreadable");

        MvcResult badProvider = upload(c, "/app/fuel/imports", "x.csv", utf8(DKV_CSV), Map.of("provider", "XYZ"));
        assertThat(body(badProvider).get("details").get("provider").asString()).isEqualTo("validation.invalid_value");

        Map<String, Object> mapping = Map.of("columns", Map.of("date", "Datum", "nonsense", "X"),
                "options", Map.of("currency", "EUR", "decimalComma", true));
        MvcResult badMapping = c.put("/app/fuel/mappings/UTA", mapping);
        assertThat(body(badMapping).get("details").get("columns.nonsense").asString())
                .isEqualTo("validation.invalid_value");
        JsonNode saved = body(c.put("/app/fuel/mappings/UTA", Map.of("columns", Map.of("date", "Datum"),
                "options", Map.of("currency", "eur", "decimalComma", true))));
        assertThat(saved.get("options").get("currency").asString()).isEqualTo("EUR");
        assertThat(c.get("/app/fuel/mappings/NOPE").getResponse().getStatus()).isEqualTo(404);

        AppUser dispatcher = createUser("dispatcher-fuel" + firma.getId() + "@test.local", "FuelDisp-1",
                Role.DISPATCHER, firma.getId(), true);
        Client d = client();
        assertThat(d.login(dispatcher.getLogin(), "FuelDisp-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/fuel/transactions").getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(d, "/app/fuel/imports", "x.csv", utf8(DKV_CSV), Map.of()).getResponse().getStatus())
                .isEqualTo(403);
    }
}
