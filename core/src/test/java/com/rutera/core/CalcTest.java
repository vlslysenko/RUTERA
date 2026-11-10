package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trip calculator: plan with breaks and rests, arrival, cost lines (fuel from the firma's fuel prices, driver pay
 * from the pay rate, tolls from the most specific tariff), hidden pay, validation and the tariff table. Zone UTC.
 */
class CalcTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Calc Test " + n);
        firma.setShortCode("CALC" + n);
        firma.setCountry("PL");
        firma.setTimezone("UTC");
        firma.setBaseCurrency("PLN");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-calc" + n + "@test.local", "CalcOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "CalcOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private static Map<String, Object> tariff(String country, String euroClass, Integer axlesMin, double rate,
                                              String currency) {
        Map<String, Object> t = new HashMap<>();
        t.put("country", country);
        t.put("euroClass", euroClass);
        t.put("axlesMin", axlesMin);
        t.put("ratePerKm", rate);
        t.put("currency", currency);
        return t;
    }

    private static Map<String, Object> trip(long vehicleId, List<Long> driverIds, String driverState) {
        Map<String, Object> body = new HashMap<>();
        body.put("start", "2026-11-02T06:00:00Z");
        body.put("vehicleId", vehicleId);
        body.put("driverIds", driverIds);
        body.put("driverState", driverState);
        body.put("legs", List.of(Map.of("name", "Poznań - Köln", "distanceKm", 1000, "serviceMin", 60,
                "countries", List.of(Map.of("country", "PL", "km", 300), Map.of("country", "de", "km", 700)))));
        return body;
    }

    private static JsonNode line(JsonNode plan, String kind, String country) {
        for (JsonNode l : plan.get("costs").get("lines")) {
            if (l.get("kind").asString().equals(kind)
                    && (country == null || country.equals(l.get("country").asString()))) {
                return l;
            }
        }
        return null;
    }

    @Test
    void aPlanWithRestsArrivalAndCosts() throws Exception {
        Client c = newFirmaOwner();
        Map<String, Object> vehicle = new HashMap<>(Map.of("kind", "TRACTOR", "plate", "PO 7001C",
                "euroClass", "EURO_6", "axles", 5, "grossWeightKg", 40000, "avgL100km", 30));
        long vehicleId = id(c.post("/app/fleet/vehicles", vehicle));
        long driverId = id(c.post("/app/drivers", Map.of("firstName", "Marek", "lastName", "Kalkulator")));
        id(c.post("/app/drivers/" + driverId + "/pay-rates", Map.of("validFrom", "2026-01-01", "monthlyBase", 3000,
                "perDiemHome", 30, "perDiemAbroad", 50, "perKm", 0.10, "currency", "EUR")));
        Map<String, Object> diesel = new HashMap<>(Map.of("at", "2026-10-30T10:00:00Z", "product", "DIESEL",
                "quantity", 100, "net", 500, "gross", 615, "currency", "PLN", "country", "PL"));
        id(c.post("/app/fuel/transactions", diesel));
        id(c.post("/app/calc/toll-tariffs", tariff("DE", null, null, 0.30, "EUR")));
        id(c.post("/app/calc/toll-tariffs", tariff("DE", "EURO_6", 5, 0.348, "EUR")));
        id(c.post("/app/calc/toll-tariffs", tariff("PL", null, null, 0.20, "PLN")));

        MvcResult res = c.post("/app/calc/trip", trip(vehicleId, List.of(driverId), "FRESH"));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode plan = body(res);
        assertThat(plan.get("eta").asString()).startsWith("2026-11-03T07:47");
        JsonNode totals = plan.get("totals");
        assertThat(totals.get("distanceKm").asDouble()).isEqualTo(1000.0);
        assertThat(totals.get("drivingMin").asInt()).isEqualTo(857);
        assertThat(totals.get("breakMin").asInt()).isEqualTo(90);
        assertThat(totals.get("workMin").asInt()).isEqualTo(60);
        assertThat(totals.get("dailyRests").asInt()).isEqualTo(1);
        assertThat(totals.get("days").asInt()).isEqualTo(2);
        assertThat(plan.get("timeline").get(5).get("kind").asString()).isEqualTo("REDUCED_DAILY_REST");
        assertThat(plan.get("timeline").get(5).get("kmFrom").asDouble()).isEqualTo(700.1);
        assertThat(plan.get("driverStates").get(0).get("source").asString()).isEqualTo("FRESH");
        assertThat(plan.get("payHidden").asBoolean()).isFalse();

        // Fuel per country as driven: PL from the firma's own card purchases there, DE from the bulletin (V18).
        JsonNode plFuel = line(plan, "FUEL", "PL");
        assertThat(plFuel.get("source").asString()).isEqualTo("OWN_TX");
        assertThat(plFuel.get("quantity").asDouble()).isEqualTo(90.0);
        assertThat(plFuel.get("rate").asDouble()).isEqualTo(5.0);
        assertThat(plFuel.get("amount").asDouble()).isEqualTo(450.0);
        JsonNode deFuel = line(plan, "FUEL", "DE");
        assertThat(deFuel.get("source").asString()).isEqualTo("REFERENCE");
        assertThat(deFuel.get("quantity").asDouble()).isEqualTo(210.0);
        assertThat(deFuel.get("rate").asDouble()).as("net, without VAT").isEqualTo(1.918);
        assertThat(deFuel.get("currency").asString()).isEqualTo("EUR");
        assertThat(deFuel.get("priceDate").asString()).isEqualTo("2026-11-02");
        assertThat(deFuel.get("amount").asDouble()).isEqualTo(402.78);
        assertThat(line(plan, "DRIVER_KM", null).get("amount").asDouble()).isEqualTo(100.0);
        assertThat(line(plan, "DRIVER_PER_DIEM", null).get("amount").asDouble()).as("abroad, 2 days").isEqualTo(100.0);
        assertThat(line(plan, "DRIVER_BASE", null).get("amount").asDouble()).isEqualTo(200.0);
        assertThat(line(plan, "TOLL", "DE").get("rate").asDouble()).as("the most specific tariff").isEqualTo(0.348);
        assertThat(line(plan, "TOLL", "DE").get("amount").asDouble()).isEqualTo(243.6);
        assertThat(line(plan, "TOLL", "PL").get("amount").asDouble()).isEqualTo(60.0);

        // Toll km of a country: only those are charged; without them all km of the country count.
        Map<String, Object> tolled = trip(vehicleId, List.of(driverId), "FRESH");
        tolled.put("legs", List.of(Map.of("name", "Poznań - Köln", "distanceKm", 1000, "countries",
                List.of(Map.of("country", "PL", "km", 300, "tollKm", 120), Map.of("country", "DE", "km", 700)))));
        JsonNode tolledPlan = body(c.post("/app/calc/trip", tolled));
        assertThat(line(tolledPlan, "TOLL", "PL").get("quantity").asDouble()).isEqualTo(120.0);
        assertThat(line(tolledPlan, "TOLL", "PL").get("amount").asDouble()).isEqualTo(24.0);
        assertThat(line(tolledPlan, "TOLL", "DE").get("quantity").asDouble()).isEqualTo(700.0);

        // The operator keeps the reference prices; a newer row of the start day wins over the bulletin's.
        Client operator = client();
        createUser("operator-calc@test.local", "OperatorCalc-1", Role.SUPERUSER, null, true);
        assertThat(operator.login("operator-calc@test.local", "OperatorCalc-1").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> dePrice = new HashMap<>(Map.of("country", "DE", "product", "DIESEL", "gross", 1.785,
                "vatRate", 19, "currency", "EUR", "priceDate", "2026-11-02", "source", "TEST"));
        assertThat(c.post("/app/calc/fuel-prices", dePrice).getResponse().getStatus()).as("operator only").isEqualTo(403);
        JsonNode saved = body(operator.post("/app/calc/fuel-prices", dePrice));
        assertThat(saved.get("net").asDouble()).as("gross without 19 % VAT").isEqualTo(1.5);
        assertThat(saved.get("kind").asString()).isEqualTo("AVERAGE");
        Map<String, Object> later = new HashMap<>(Map.of("country", "DE", "product", "DIESEL", "net", 9.99,
                "currency", "EUR", "priceDate", "2026-12-01", "source", "TEST"));
        operator.post("/app/calc/fuel-prices", later);
        JsonNode prices = body(c.get("/app/calc/fuel-prices"));
        assertThat(prices.size()).as("27 bulletin rows and 2 more").isEqualTo(29);
        JsonNode perCountry = body(c.post("/app/calc/trip", trip(vehicleId, List.of(driverId), "FRESH")));
        assertThat(line(perCountry, "FUEL", "DE").get("rate").asDouble()).as("valid on the start day").isEqualTo(1.5);

        // Own purchases with gross only: net without the country's VAT rate; no reference price: the firma average.
        id(c.post("/app/fuel/transactions", new HashMap<>(Map.of("at", "2026-10-31T10:00:00Z", "product", "DIESEL",
                "quantity", 100, "gross", 238, "currency", "EUR", "country", "DE"))));
        Map<String, Object> swiss = trip(vehicleId, List.of(driverId), "FRESH");
        swiss.put("legs", List.of(Map.of("name", "Köln - Basel - Zürich", "distanceKm", 600, "countries",
                List.of(Map.of("country", "DE", "km", 500), Map.of("country", "CH", "km", 100)))));
        JsonNode swissPlan = body(c.post("/app/calc/trip", swiss));
        assertThat(line(swissPlan, "FUEL", "DE").get("rate").asDouble()).as("238 / 1.19 / 100").isEqualTo(2.0);
        assertThat(line(swissPlan, "FUEL", "DE").get("source").asString()).isEqualTo("OWN_TX");
        assertThat(line(swissPlan, "FUEL", "CH").get("source").asString()).isEqualTo("OWN_TX_ALL");
        assertThat(line(swissPlan, "FUEL", "CH").get("rate").asDouble()).isEqualTo(5.0);

        // A tractor's card holds the tractor alone (2 axles, 18 t): the toll class is the combination's (5 axles, 40 t).
        long tractorId = id(c.post("/app/fleet/vehicles", new HashMap<>(Map.of("kind", "TRACTOR", "plate", "PO 7002C",
                "euroClass", "EURO_6", "axles", 2, "grossWeightKg", 18000, "avgL100km", 30))));
        JsonNode tractorPlan = body(c.post("/app/calc/trip", trip(tractorId, List.of(driverId), "FRESH")));
        assertThat(line(tractorPlan, "TOLL", "DE").get("rate").asDouble()).as("5 axles as a combination").isEqualTo(0.348);
        tolled.put("legs", List.of(Map.of("distanceKm", 300, "countries",
                List.of(Map.of("country", "PL", "km", 300, "tollKm", 301)))));
        assertThat(body(c.post("/app/calc/trip", tolled)).get("details").get("legs[0].countries").asString())
                .isEqualTo("validation.invalid_value");
        Map<String, Double> totalsByCurrency = new HashMap<>();
        plan.get("costs").get("totals").forEach(t -> totalsByCurrency.put(t.get("currency").asString(),
                t.get("amount").asDouble()));
        assertThat(totalsByCurrency).containsEntry("EUR", 1046.38).containsEntry("PLN", 510.0);
        assertThat(plan.get("costs").get("missing")).isEmpty();

        JsonNode tacho = body(c.post("/app/calc/trip", trip(vehicleId, List.of(driverId), null)));
        assertThat(tacho.get("costs").get("missing").get(0).asString()).isEqualTo("calc.missing.tacho_data");

        Map<String, Object> crew = trip(vehicleId, List.of(driverId), "FRESH");
        crew.put("crewSize", 2);
        JsonNode two = body(c.post("/app/calc/trip", crew));
        assertThat(two.get("driverStates")).hasSize(2);
        assertThat(two.get("totals").get("breakMin").asInt()).isZero();
        assertThat(two.get("costs").get("missing")).as("an empty crew slot is no missing rate").isEmpty();
        JsonNode km = line(two, "DRIVER_KM", null);
        assertThat(km.get("quantity").asDouble()).as("only the km driver 1 drives (540 of 857 min)").isEqualTo(630.1);
        assertThat(km.get("amount").asDouble()).isEqualTo(63.01);

        AppUser dispatcher = createUser("dispatcher-calc" + firma.getId() + "@test.local", "CalcDisp-1",
                Role.DISPATCHER, firma.getId(), true);
        Client d = client();
        assertThat(d.login(dispatcher.getLogin(), "CalcDisp-1").getResponse().getStatus()).isEqualTo(200);
        JsonNode hidden = body(d.post("/app/calc/trip", trip(vehicleId, List.of(driverId), "FRESH")));
        assertThat(hidden.get("payHidden").asBoolean()).isTrue();
        assertThat(line(hidden, "DRIVER_KM", null)).isNull();
        AppUser accountant = createUser("accountant-calc" + firma.getId() + "@test.local", "CalcAcc-1",
                Role.ACCOUNTANT, firma.getId(), true);
        Client a = client();
        assertThat(a.login(accountant.getLogin(), "CalcAcc-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(a.post("/app/calc/toll-tariffs", tariff("CZ", null, null, 0.1, "CZK")).getResponse().getStatus())
                .as("tariffs need trip.trip/manage").isEqualTo(403);
    }

    @Test
    void invalidInputAndTheTariffTable() throws Exception {
        Client c = newFirmaOwner();
        Map<String, Object> noLegs = new HashMap<>(Map.of("start", "2026-11-02T06:00:00Z"));
        MvcResult res = c.post("/app/calc/trip", noLegs);
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(res).get("details").get("legs").asString()).isEqualTo("validation.required");

        Map<String, Object> wrongSplit = new HashMap<>(Map.of("start", "2026-11-02T06:00:00Z", "legs",
                List.of(Map.of("distanceKm", 500, "countries", List.of(Map.of("country", "PL", "km", 100))))));
        assertThat(body(c.post("/app/calc/trip", wrongSplit)).get("details").get("legs[0].countries").asString())
                .isEqualTo("validation.invalid_value");

        Map<String, Object> manual = new HashMap<>(Map.of("start", "2026-11-02T06:00:00Z", "driverState", "MANUAL",
                "legs", List.of(Map.of("distanceKm", 70))));
        assertThat(body(c.post("/app/calc/trip", manual)).get("details").get("manualState").asString())
                .isEqualTo("validation.required");
        manual.put("manualState", Map.of("drivingSinceBreakMin", 250, "drivingInShiftMin", 500));
        JsonNode plan = body(c.post("/app/calc/trip", manual));
        assertThat(plan.get("timeline").get(0).get("minutes").asInt()).isEqualTo(20);
        assertThat(plan.get("timeline").get(1).get("kind").asString()).isEqualTo("BREAK");
        assertThat(plan.get("costs").get("missing").get(0).asString()).isEqualTo("calc.missing.consumption");

        MvcResult noRouting = c.post("/app/calc/route", Map.of("points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.5, "lng", 13.4))));
        assertThat(noRouting.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(noRouting).get("code").asString()).isEqualTo("calc.routing.disabled");

        MvcResult bad = c.post("/app/calc/toll-tariffs", tariff("XX1", "EURO_9", 0, 0.1, "EURO"));
        JsonNode details = body(bad).get("details");
        assertThat(details.get("country").asString()).isEqualTo("validation.invalid_format");
        assertThat(details.get("euroClass").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("axlesMin").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("currency").asString()).isEqualTo("validation.invalid_format");
        long id = id(c.post("/app/calc/toll-tariffs", tariff("at", null, null, 0.4, "eur")));
        Map<String, Object> change = tariff("AT", "EURO_6", null, 0.42, "EUR");
        assertThat(body(c.put("/app/calc/toll-tariffs/" + id, change)).get("ratePerKm").asDouble()).isEqualTo(0.42);
        assertThat(body(c.get("/app/calc/toll-tariffs"))).hasSize(1);
        assertThat(c.delete("/app/calc/toll-tariffs/" + id).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get("/app/calc/toll-tariffs"))).isEmpty();
    }
}
