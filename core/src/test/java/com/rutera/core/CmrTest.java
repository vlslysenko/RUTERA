package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMR notes: prefill from an order and a trip, numbering, editing drafts, issuing (checks and the stored PDF of four
 * copies), cancelling, and the PDF itself (pages, Polish and Cyrillic text, the draft watermark).
 */
class CmrTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();
    private static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        Firma firma = new Firma();
        firma.setName("Trans-Pol Sp. z o.o. " + n);
        firma.setShortCode("CMR" + n);
        firma.setCountry("PL");
        firma.setAddress("ul. Łódzka 5\n61-001 Poznań");
        firma.setVatId("PL7770001112");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-cmr" + n + "@test.local", "CmrOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "CmrOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private static Map<String, Object> stop(String kind, Long orderId, String name, String address, String country) {
        Map<String, Object> s = new HashMap<>();
        s.put("kind", kind);
        s.put("orderId", orderId);
        s.put("name", name);
        s.put("address", address);
        s.put("country", country);
        return s;
    }

    private static String pdfText(byte[] bytes, int[] pages) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            pages[0] = doc.getNumberOfPages();
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aNoteFromAnOrderIsEditedIssuedAndCancelled() throws Exception {
        Client c = newFirmaOwner();
        long customer = id(c.post("/app/contractors", Map.of("name", "Acme Food Sp. z o.o.", "country", "PL",
                "address", "ul. Spożywcza 1, 60-100 Poznań", "vatId", "PL1234567890")));
        long tractor = id(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "PO 1001C")));
        long trailer = id(c.post("/app/fleet/vehicles", Map.of("kind", "SEMI_TRAILER", "plate", "PO 2002T")));
        Map<String, Object> order = new HashMap<>();
        order.put("orderDate", YEAR + "-03-01");
        order.put("customerId", customer);
        order.put("cargo", "Mrożonki, 33 palety");
        order.put("weightKg", 18500);
        order.put("loadingPlace", "Poznań, ul. Magazynowa 1");
        order.put("loadingCountry", "PL");
        order.put("loadingDate", YEAR + "-03-02");
        order.put("unloadingPlace", "Berlin, Lagerstraße 5");
        order.put("unloadingCountry", "DE");
        long orderId = id(c.post("/app/orders", order));
        Map<String, Object> trip = new HashMap<>();
        trip.put("vehicleId", tractor);
        trip.put("trailerId", trailer);
        trip.put("orderIds", List.of(orderId));
        trip.put("stops", List.of(stop("LOADING", orderId, null, "Poznań, ul. Magazynowa 1", "PL"),
                stop("UNLOADING", orderId, null, "Berlin, Lagerstraße 5", "DE")));
        long tripId = id(c.post("/app/trips", trip));

        MvcResult created = c.post("/app/cmr/from-order/" + orderId, null);
        long cmrId = id(created);
        JsonNode cmr = body(created);
        assertThat(cmr.get("number").asString()).isEqualTo("CMR/" + YEAR + "/0001");
        assertThat(cmr.get("status").asString()).isEqualTo("DRAFT");
        assertThat(cmr.get("sender").get("name").asString()).isEqualTo("Acme Food Sp. z o.o.");
        assertThat(cmr.get("sender").get("vatId").asString()).isEqualTo("PL1234567890");
        assertThat(cmr.get("carrier").get("name").asString()).startsWith("Trans-Pol");
        assertThat(cmr.get("consignee").get("address").asString()).isEqualTo("Berlin, Lagerstraße 5");
        assertThat(cmr.get("loadingDate").asString()).isEqualTo(YEAR + "-03-02");
        assertThat(cmr.get("establishedPlace").asString()).isEqualTo("Poznań");
        assertThat(cmr.get("vehiclePlates").asString()).isEqualTo("PO 1001C / PO 2002T");
        assertThat(cmr.get("lines").get(0).get("nature").asString()).isEqualTo("Mrożonki, 33 palety");
        assertThat(cmr.get("lines").get(0).get("grossWeightKg").asDouble()).isEqualTo(18500.0);
        assertThat(cmr.get("order").get("id").asLong()).isEqualTo(orderId);
        assertThat(cmr.get("trip").get("id").asLong()).isEqualTo(tripId);
        assertThat(cmr.get("hasPdf").asBoolean()).isFalse();

        MvcResult draftPdf = c.get("/app/cmr/" + cmrId + "/pdf?copies=3");
        assertThat(draftPdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(draftPdf.getResponse().getContentType()).isEqualTo("application/pdf");
        int[] pages = new int[1];
        String text = pdfText(draftPdf.getResponse().getContentAsByteArray(), pages);
        assertThat(pages[0]).isEqualTo(3);
        assertThat(text).contains("CMR/" + YEAR + "/0001").contains("Poznań").contains("Mrożonki");
        assertThat(text.replaceAll("\\s", "")).contains("DRAFT");

        Map<String, Object> edit = new HashMap<>();
        edit.put("orderId", orderId);
        edit.put("tripId", tripId);
        edit.put("sender", cmr.get("sender"));
        edit.put("carrier", cmr.get("carrier"));
        edit.put("consignee", Map.of("name", "ООО Тест Логистик", "address", "Berlin, Lagerstraße 5",
                "country", "de"));
        edit.put("loadingPlace", "Poznań, ul. Magazynowa 1");
        edit.put("loadingCountry", "PL");
        edit.put("loadingDate", YEAR + "-03-02");
        edit.put("deliveryPlace", "Berlin, Lagerstraße 5");
        edit.put("deliveryCountry", "DE");
        edit.put("lines", List.of(Map.of("marks", "EUR", "packages", 33, "packing", "palety", "nature", "Mrożonki",
                "grossWeightKg", 18000), Map.of("nature", "Opakowania zwrotne", "grossWeightKg", 500)));
        edit.put("paymentTerms", "PAID");
        edit.put("cashOnDelivery", Map.of("amount", 100, "currency", "eur"));
        edit.put("signatures", Map.of("carrier", Map.of("name", "Jan Kierowca", "place", "Poznań",
                "date", YEAR + "-03-02")));
        JsonNode edited = body(c.put("/app/cmr/" + cmrId, edit));
        assertThat(edited.get("number").asString()).as("kept").isEqualTo("CMR/" + YEAR + "/0001");
        assertThat(edited.get("consignee").get("country").asString()).isEqualTo("DE");
        assertThat(edited.get("lines")).hasSize(2);
        assertThat(edited.get("cashOnDelivery").get("currency").asString()).isEqualTo("EUR");
        assertThat(edited.get("establishedPlace").isNull()).as("a full replacement").isTrue();

        JsonNode issued = body(c.post("/app/cmr/" + cmrId + "/issue", null));
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("hasPdf").asBoolean()).isTrue();
        assertThat(issued.get("issuedAt").isNull()).isFalse();
        MvcResult stored = c.get("/app/cmr/" + cmrId + "/pdf");
        String issuedText = pdfText(stored.getResponse().getContentAsByteArray(), pages);
        assertThat(pages[0]).as("all four copies").isEqualTo(4);
        assertThat(issuedText).contains("ООО Тест Логистик").contains("18500");
        assertThat(issuedText.replaceAll("\\s", "")).doesNotContain("DRAFT");

        assertThat(c.put("/app/cmr/" + cmrId, edit).getResponse().getStatus()).isEqualTo(409);
        assertThat(c.delete("/app/cmr/" + cmrId).getResponse().getStatus()).isEqualTo(409);
        assertThat(body(c.post("/app/cmr/" + cmrId + "/cancel", null)).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(c.post("/app/cmr/" + cmrId + "/cancel", null).getResponse().getStatus()).isEqualTo(409);

        JsonNode fromTrip = body(c.post("/app/cmr/from-trip/" + tripId, null));
        assertThat(fromTrip).hasSize(1);
        assertThat(fromTrip.get(0).get("number").asString()).isEqualTo("CMR/" + YEAR + "/0002");
        JsonNode byTrip = body(c.get("/app/cmr?tripId=" + tripId));
        assertThat(byTrip.get("total").asLong()).isEqualTo(2);
        assertThat(body(c.get("/app/cmr?status=CANCELLED")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/cmr?q=acme")).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void draftsNeedTheirBoxesBeforeIssueAndNumbersAreUnique() throws Exception {
        Client c = newFirmaOwner();
        long empty = id(c.post("/app/cmr", Map.of()));
        MvcResult notReady = c.post("/app/cmr/" + empty + "/issue", null);
        assertThat(notReady.getResponse().getStatus()).isEqualTo(422);
        JsonNode details = body(notReady).get("details");
        assertThat(details.get("sender.name").asString()).isEqualTo("validation.required");
        assertThat(details.get("carrier.name").asString()).isEqualTo("validation.required");
        assertThat(details.get("loadingPlace").asString()).isEqualTo("validation.required");
        assertThat(details.get("lines").asString()).isEqualTo("validation.required");

        String number = body(c.get("/app/cmr/" + empty)).get("number").asString();
        MvcResult taken = c.post("/app/cmr", Map.of("number", number.toLowerCase()));
        assertThat(body(taken).get("details").get("number").asString()).isEqualTo("trip.cmr.number_taken");
        MvcResult badCountry = c.post("/app/cmr", Map.of("sender", Map.of("name", "X", "country", "Poland")));
        assertThat(body(badCountry).get("details").get("sender.country").asString())
                .isEqualTo("validation.invalid_format");
        assertThat(c.get("/app/cmr/" + empty + "/pdf?copies=5").getResponse().getStatus()).isEqualTo(422);

        long tractor = id(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "PO 3003C")));
        Map<String, Object> trip = new HashMap<>();
        trip.put("vehicleId", tractor);
        trip.put("orderIds", List.of());
        trip.put("plannedStart", YEAR + "-04-01T06:00:00Z");
        trip.put("stops", List.of(stop("LOADING", null, "Magazyn Centralny", "Września, ul. Polna 2", "PL"),
                stop("UNLOADING", null, "Lager Süd", "München, Industriestraße 9", "DE")));
        long tripId = id(c.post("/app/trips", trip));
        JsonNode drafts = body(c.post("/app/cmr/from-trip/" + tripId, null));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).get("sender").get("name").asString()).isEqualTo("Magazyn Centralny");
        assertThat(drafts.get(0).get("deliveryPlace").asString()).isEqualTo("München, Industriestraße 9");
        assertThat(drafts.get(0).get("establishedPlace").asString()).isEqualTo("Września");
        assertThat(c.delete("/app/cmr/" + empty).getResponse().getStatus()).isEqualTo(204);
    }
}
