package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
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
 * Contractors, orders and trips: numbering, the order status that follows the trip, the full replacement of crew,
 * orders and stops, stop marking, validation and privileges. Every test runs in a firma of its own.
 */
class TripOrderTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();
    private static final int YEAR = LocalDate.now(ZoneOffset.UTC).getYear();

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Trip Test " + n);
        firma.setShortCode("TRIP" + n);
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-trip" + n + "@test.local", "TripOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "TripOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private long customer(Client c, String name) throws Exception {
        return id(c.post("/app/contractors", Map.of("name", name, "country", "PL")));
    }

    private long vehicle(Client c, String kind, String plate) throws Exception {
        return id(c.post("/app/fleet/vehicles", Map.of("kind", kind, "plate", plate)));
    }

    private long driver(Client c, String first, String last) throws Exception {
        return id(c.post("/app/drivers", Map.of("firstName", first, "lastName", last)));
    }

    private Map<String, Object> order(long customerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderDate", YEAR + "-03-01");
        body.put("customerId", customerId);
        body.put("price", 2500);
        body.put("currency", "eur");
        body.put("loadingPlace", "Poznań, ul. Magazynowa 1");
        body.put("loadingCountry", "pl");
        body.put("loadingDate", YEAR + "-03-02");
        body.put("unloadingPlace", "Berlin, Lagerstraße 5");
        body.put("unloadingCountry", "DE");
        body.put("unloadingDate", YEAR + "-03-03");
        return body;
    }

    private static Map<String, Object> stop(Long id, String kind, Long orderId, String address, String country) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", id);
        s.put("kind", kind);
        s.put("orderId", orderId);
        s.put("address", address);
        s.put("country", country);
        return s;
    }

    private static Map<String, Object> trip(long vehicleId, List<Long> orderIds, List<Map<String, Object>> stops) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", vehicleId);
        body.put("orderIds", orderIds);
        body.put("stops", stops);
        return body;
    }

    @Test
    void contractorsHaveAUniqueVatIdAndASoftDelete() throws Exception {
        Client c = newFirmaOwner();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Kowalski Logistics Sp. z o.o.");
        body.put("vatId", "pl 123-456.78-90");
        body.put("country", "pl");
        body.put("invoiceEmails", List.of("faktury@kowalski.example", " ", "faktury@kowalski.example"));
        body.put("paymentDays", 30);
        body.put("lang", "PL");
        MvcResult created = c.post("/app/contractors", body);
        long id = id(created);
        JsonNode k = body(created);
        assertThat(k.get("vatId").asString()).isEqualTo("PL1234567890");
        assertThat(k.get("country").asString()).isEqualTo("PL");
        assertThat(k.get("lang").asString()).isEqualTo("pl");
        assertThat(k.get("invoiceEmails")).hasSize(1);
        assertThat(k.get("isCustomer").asBoolean()).isTrue();
        assertThat(k.get("isCarrier").asBoolean()).isFalse();

        body.put("name", "Kowalski bis");
        MvcResult taken = c.post("/app/contractors", body);
        assertThat(taken.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(taken).get("details").get("vatId").asString()).isEqualTo("trip.contractor.vat_taken");

        Map<String, Object> carrier = new HashMap<>(Map.of("name", "Subcarrier GmbH", "country", "DE",
                "isCustomer", false, "isCarrier", true));
        id(c.post("/app/contractors", carrier));
        assertThat(body(c.get("/app/contractors?role=CARRIER"))).hasSize(1);
        assertThat(body(c.get("/app/contractors?role=CUSTOMER"))).hasSize(1);
        assertThat(body(c.get("/app/contractors?q=sub"))).hasSize(1);

        Map<String, Object> badMail = new HashMap<>(Map.of("name", "X", "country", "PL",
                "invoiceEmails", List.of("not-a-mail")));
        assertThat(body(c.post("/app/contractors", badMail)).get("details").get("invoiceEmails").asString())
                .isEqualTo("validation.invalid_format");

        assertThat(c.delete("/app/contractors/" + id).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/contractors/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.post("/app/contractors", body).getResponse().getStatus()).as("the VAT id is free again")
                .isEqualTo(201);
    }

    @Test
    void anOrderFollowsItsTripFromPlanningToDone() throws Exception {
        Client c = newFirmaOwner();
        long customerId = customer(c, "Acme Food");
        long tractor = vehicle(c, "TRACTOR", "PO 1001");
        long trailer = vehicle(c, "SEMI_TRAILER", "PO 1002");
        long anna = driver(c, "Anna", "Nowak");
        long piotr = driver(c, "Piotr", "Lis");

        MvcResult first = c.post("/app/orders", order(customerId));
        long orderId = id(first);
        assertThat(body(first).get("number").asString()).isEqualTo("O-" + YEAR + "-0001");
        assertThat(body(first).get("status").asString()).isEqualTo("NEW");
        assertThat(body(first).get("currency").asString()).isEqualTo("EUR");
        assertThat(body(first).get("loading").get("country").asString()).isEqualTo("PL");
        assertThat(body(first).get("customer").get("name").asString()).isEqualTo("Acme Food");
        assertThat(body(c.post("/app/orders", order(customerId))).get("number").asString())
                .isEqualTo("O-" + YEAR + "-0002");

        Map<String, Object> request = new HashMap<>(Map.of("vehicleId", tractor, "trailerId", trailer,
                "mainDriverId", anna, "secondDriverId", piotr));
        MvcResult created = c.post("/app/orders/" + orderId + "/trip", request);
        long tripId = id(created);
        JsonNode trip = body(created);
        assertThat(trip.get("number").asString()).isEqualTo("T-" + YEAR + "-0001");
        assertThat(trip.get("status").asString()).isEqualTo("PLANNED");
        assertThat(trip.get("vehicle").get("plate").asString()).isEqualTo("PO 1001");
        assertThat(trip.get("trailer").get("plate").asString()).isEqualTo("PO 1002");
        assertThat(trip.get("drivers").get(0).get("role").asString()).isEqualTo("MAIN");
        assertThat(trip.get("drivers").get(0).get("name").asString()).isEqualTo("Anna Nowak");
        assertThat(trip.get("drivers").get(1).get("role").asString()).isEqualTo("SECOND");
        assertThat(trip.get("orders").get(0).get("id").asLong()).isEqualTo(orderId);
        assertThat(trip.get("stops")).hasSize(2);
        assertThat(trip.get("stops").get(0).get("kind").asString()).isEqualTo("LOADING");
        assertThat(trip.get("stops").get(1).get("country").asString()).isEqualTo("DE");
        assertThat(trip.get("plannedStart").asString()).startsWith(YEAR + "-03-02");
        assertThat(body(c.get("/app/orders/" + orderId)).get("status").asString()).isEqualTo("PLANNED");
        assertThat(body(c.get("/app/orders/" + orderId)).get("trip").get("number").asString())
                .isEqualTo("T-" + YEAR + "-0001");
        assertThat(c.post("/app/orders/" + orderId + "/trip", request).getResponse().getStatus()).isEqualTo(409);

        Map<String, Object> running = trip(tractor, List.of(orderId), List.of(
                stop(trip.get("stops").get(0).get("id").asLong(), "LOADING", orderId, "Poznań", "PL"),
                stop(trip.get("stops").get(1).get("id").asLong(), "UNLOADING", orderId, "Berlin", "DE")));
        running.put("status", "RUNNING");
        running.put("trailerId", trailer);
        running.put("mainDriverId", anna);
        MvcResult started = c.put("/app/trips/" + tripId, running);
        assertThat(started.getResponse().getStatus()).as(started.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(started).get("actualStart").isNull()).isFalse();
        assertThat(body(started).get("drivers")).hasSize(1);
        assertThat(body(started).get("stops").get(0).get("id").asLong())
                .isEqualTo(trip.get("stops").get(0).get("id").asLong());
        assertThat(body(c.get("/app/orders/" + orderId)).get("status").asString()).isEqualTo("IN_PROGRESS");

        long stopId = trip.get("stops").get(0).get("id").asLong();
        MvcResult marked = c.patch("/app/trips/" + tripId + "/stops/" + stopId, Map.of("status", "DONE"));
        assertThat(marked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(marked).get("status").asString()).isEqualTo("DONE");
        assertThat(body(marked).get("actualAt").isNull()).isFalse();

        running.put("status", "DONE");
        assertThat(body(c.put("/app/trips/" + tripId, running)).get("actualEnd").isNull()).isFalse();
        assertThat(body(c.get("/app/orders/" + orderId)).get("status").asString()).isEqualTo("DONE");
        assertThat(c.delete("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(409);
        assertThat(c.delete("/app/orders/" + orderId).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aPutReplacesOrdersAndStopsAndCancellingLetsTheOrdersGo() throws Exception {
        Client c = newFirmaOwner();
        long customerId = customer(c, "Beta Steel");
        long truck = vehicle(c, "TRUCK", "WA 2001");
        long o1 = id(c.post("/app/orders", order(customerId)));
        long o2 = id(c.post("/app/orders", order(customerId)));

        MvcResult created = c.post("/app/trips", trip(truck, List.of(o1), List.of(
                stop(null, "LOADING", o1, "Warszawa", "PL"),
                stop(null, "BORDER", null, "Świecko", "PL"),
                stop(null, "UNLOADING", o1, "Paris", "FR"))));
        long tripId = id(created);
        JsonNode stops = body(created).get("stops");
        long border = stops.get(1).get("id").asLong();

        MvcResult replaced = c.put("/app/trips/" + tripId, trip(truck, List.of(o1, o2), List.of(
                stop(border, "BORDER", null, "Świecko", "PL"),
                stop(null, "LOADING", o2, "Łódź", "PL"))));
        assertThat(replaced.getResponse().getStatus()).as(replaced.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode now = body(replaced).get("stops");
        assertThat(now).hasSize(2);
        assertThat(now.get(0).get("id").asLong()).as("the border stop keeps its id").isEqualTo(border);
        assertThat(now.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(now.get(1).get("seq").asInt()).isEqualTo(2);
        assertThat(body(replaced).get("orders")).hasSize(2);
        assertThat(body(c.get("/app/orders/" + o2)).get("status").asString()).isEqualTo("PLANNED");

        MvcResult withoutO1 = c.put("/app/trips/" + tripId, trip(truck, List.of(o2), List.of(
                stop(border, "BORDER", null, "Świecko", "PL"))));
        assertThat(withoutO1.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(c.get("/app/orders/" + o1)).get("status").asString()).isEqualTo("NEW");
        assertThat(body(c.get("/app/orders/" + o1)).get("trip").isNull()).isTrue();

        // A stop of another trip, or an order that is not on the trip, is refused.
        MvcResult foreignStop = c.put("/app/trips/" + tripId, trip(truck, List.of(o2), List.of(
                stop(999_999L, "BORDER", null, "X", "PL"), stop(null, "LOADING", o1, "Y", "PL"))));
        JsonNode details = body(foreignStop).get("details");
        assertThat(details.get("stops[0].id").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("stops[1].orderId").asString()).isEqualTo("validation.invalid_value");

        Map<String, Object> cancelled = trip(truck, List.of(o2), List.of(stop(border, "BORDER", null, "Świecko", "PL")));
        cancelled.put("status", "CANCELLED");
        MvcResult cancel = c.put("/app/trips/" + tripId, cancelled);
        assertThat(cancel.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(cancel).get("orders")).isEmpty();
        assertThat(body(c.get("/app/orders/" + o2)).get("status").asString()).isEqualTo("NEW");
        assertThat(c.delete("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.delete("/app/orders/" + o1).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void validationSearchAndCancelledOrders() throws Exception {
        Client c = newFirmaOwner();
        long customerId = customer(c, "Gamma Chemicals");
        long truck = vehicle(c, "TRUCK", "KR 3001");
        long trailer = vehicle(c, "TRAILER", "KR 3002");
        long driverId = driver(c, "Ewa", "Kos");

        Map<String, Object> noCurrency = order(customerId);
        noCurrency.remove("currency");
        noCurrency.put("number", "ord/7");
        noCurrency.put("unloadingDate", YEAR + "-02-01");
        JsonNode details = body(c.post("/app/orders", noCurrency)).get("details");
        assertThat(details.get("currency").asString()).isEqualTo("validation.required");
        assertThat(details.get("unloadingDate").asString()).isEqualTo("validation.invalid_value");

        Map<String, Object> own = order(customerId);
        own.put("number", "ord/7");
        own.put("customerRef", "PO-778899");
        MvcResult ord7 = c.post("/app/orders", own);
        long orderId = id(ord7);
        assertThat(body(ord7).get("number").asString()).isEqualTo("ORD/7");
        assertThat(body(c.post("/app/orders", own)).get("details").get("number").asString())
                .isEqualTo("trip.order.number_taken");

        Map<String, Object> wrongVehicles = trip(trailer, List.of(), List.of());
        wrongVehicles.put("trailerId", truck);
        wrongVehicles.put("secondDriverId", driverId);
        details = body(c.post("/app/trips", wrongVehicles)).get("details");
        assertThat(details.get("vehicleId").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("trailerId").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("secondDriverId").asString()).isEqualTo("validation.invalid_value");

        long tripId = id(c.post("/app/trips", trip(truck, List.of(orderId), List.of())));
        assertThat(body(c.get("/app/trips?q=kr 30")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/trips?q=ord/7")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/trips?q=nothing")).get("total").asLong()).isZero();
        assertThat(body(c.get("/app/trips?vehicleId=" + truck)).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/orders?q=gamma")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/orders?q=778899")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/orders?status=PLANNED")).get("total").asLong()).isEqualTo(1);

        // Cancelling takes the order off its trip; a cancelled order cannot be put on a trip again.
        own.put("cancelled", true);
        MvcResult cancelled = c.put("/app/orders/" + orderId, own);
        assertThat(body(cancelled).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(body(cancelled).get("trip").isNull()).isTrue();
        assertThat(body(c.get("/app/trips/" + tripId)).get("orders")).isEmpty();
        MvcResult again = c.put("/app/trips/" + tripId, trip(truck, List.of(orderId), List.of()));
        assertThat(body(again).get("details").get("orderIds").asString()).isEqualTo("validation.invalid_value");
        own.put("cancelled", false);
        assertThat(body(c.put("/app/orders/" + orderId, own)).get("status").asString()).isEqualTo("NEW");
    }

    @Test
    void privilegesAndFirmaIsolation() throws Exception {
        Client owner = newFirmaOwner();
        long customerId = customer(owner, "Delta Paper");
        long orderId = id(owner.post("/app/orders", order(customerId)));
        long tripId = id(owner.post("/app/trips", trip(vehicle(owner, "TRUCK", "GD 4001"), List.of(orderId),
                List.of())));

        AppUser accountant = createUser("accountant-trip" + firma.getId() + "@test.local", "Acct-Pass-456",
                Role.ACCOUNTANT, firma.getId(), true);
        Client acct = client();
        acct.login(accountant.getLogin(), "Acct-Pass-456");
        assertThat(acct.get("/app/orders").getResponse().getStatus()).isEqualTo(200);
        assertThat(acct.get("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(200);
        assertThat(acct.get("/app/contractors").getResponse().getStatus()).isEqualTo(200);
        assertThat(acct.post("/app/contractors", Map.of("name", "Epsilon", "country", "PL")).getResponse()
                .getStatus()).isEqualTo(201);
        assertThat(acct.post("/app/orders", order(customerId)).getResponse().getStatus()).isEqualTo(403);
        assertThat(acct.delete("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(403);

        AppUser driverUser = createUser("driver-trip" + firma.getId() + "@test.local", "Driver-Pass-9", Role.DRIVER,
                firma.getId(), true);
        Client app = client();
        app.login(driverUser.getLogin(), "Driver-Pass-9");
        assertThat(app.get("/app/trips").getResponse().getStatus()).isEqualTo(403);
        assertThat(app.get("/app/orders").getResponse().getStatus()).isEqualTo(403);

        Client other = newFirmaOwner();
        assertThat(other.get("/app/orders/" + orderId).getResponse().getStatus()).isEqualTo(404);
        assertThat(other.get("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(404);
        assertThat(other.get("/app/contractors/" + customerId).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(other.post("/app/orders", order(customerId))).get("details").get("customerId").asString())
                .isEqualTo("validation.invalid_value");
        assertThat(body(other.get("/app/trips")).get("total").asLong()).isZero();
    }
}
