package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truck routes against a stub of Valhalla and Nominatim (a JDK HTTP server): geocoding, the truck profile from the
 * vehicle, legs with km per country and the shape, the error codes, and waypoints in the trip calculator.
 */
class RouteTest extends AbstractIntegrationTest {

    private static final HttpServer STUB;
    private static volatile String lastRoute = "";
    private static volatile String lastRawQuery = "";
    private static final java.util.concurrent.atomic.AtomicInteger FIRMAS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger PHOTON_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        STUB.createContext("/valhalla/route", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRoute = body;
            if (body.contains("\"lat\":0.0")) {
                send(ex, 400, "{\"error_code\":442,\"error\":\"No path could be found for input\"}");
                return;
            }
            int locations = body.split("\"lon\"").length - 1;
            StringBuilder legs = new StringBuilder();
            for (int i = 0; i < locations - 1; i++) {
                legs.append(i == 0 ? "" : ",").append("{\"summary\":{\"length\":280.0,\"time\":12000},"
                        + "\"shape\":\"_p~iF~ps|U_ulLnnqC\"}");
            }
            send(ex, 200, "{\"trip\":{\"legs\":[" + legs + "],\"summary\":{\"length\":280.0,\"time\":12000}}}");
        });
        STUB.createContext("/valhalla/trace_attributes", ex -> {
            ex.getRequestBody().readAllBytes();
            send(ex, 200, "{\"edges\":[{\"length\":100.0,\"road_class\":\"motorway\",\"end_node\":{\"admin_index\":0}},"
                    + "{\"length\":50.0,\"road_class\":\"secondary\",\"end_node\":{\"admin_index\":0}},"
                    + "{\"length\":130.0,\"road_class\":\"primary\",\"end_node\":{\"admin_index\":1}}],"
                    + "\"admins\":[{\"country_code\":\"PL\"},{\"country_code\":\"DE\"}]}");
        });
        STUB.createContext("/photon/api", ex -> {
            PHOTON_CALLS.incrementAndGet();
            lastRawQuery = ex.getRequestURI().getRawQuery();
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (query.contains("q=Broken")) {
                send(ex, 500, "{}");
            } else if (query.contains("q=Wroc") && query.contains("lang=en") && query.contains("limit=5")) {
                send(ex, 200, "{\"type\":\"FeatureCollection\",\"features\":["
                        + "{\"geometry\":{\"coordinates\":[17.0333,51.11],\"type\":\"Point\"},\"properties\":"
                        + "{\"osm_key\":\"place\",\"osm_value\":\"city\",\"name\":\"Wrocław\",\"city\":\"Wrocław\","
                        + "\"state\":\"województwo dolnośląskie\",\"country\":\"Polska\",\"countrycode\":\"PL\"}},"
                        + "{\"geometry\":{\"coordinates\":[17.02,51.12],\"type\":\"Point\"},\"properties\":"
                        + "{\"osm_key\":\"building\",\"street\":\"Wrocławska\",\"housenumber\":\"12\","
                        + "\"postcode\":\"55-100\",\"city\":\"Trzebnica\",\"country\":\"Polska\",\"countrycode\":\"pl\"}}]}");
            } else {
                send(ex, 200, "{\"type\":\"FeatureCollection\",\"features\":[]}");
            }
        });
        STUB.createContext("/nominatim/search", ex -> {
            lastRawQuery = ex.getRequestURI().getRawQuery();
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (query.contains("Nowhere")) {
                send(ex, 200, "[]");
            } else if (query.contains("q=Wrocław, wagnera 3&")) {
                send(ex, 200, "[{\"lat\":\"51.052\",\"lon\":\"17.060\",\"display_name\":\"Wagnera 3, Wrocław\"}]");
            } else {
                send(ex, 200, "[{\"lat\":\"52.4064\",\"lon\":\"16.9252\",\"display_name\":\"Poznań, Polska\"}]");
            }
        });
        STUB.start();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("rutera.routing.url", () -> base + "/valhalla");
        registry.add("rutera.geocoding.url", () -> base + "/nominatim");
        registry.add("rutera.places.url", () -> base + "/photon");
    }

    private Client newFirmaOwner() throws Exception {
        Firma firma = new Firma();
        firma.setName("Route Test");
        firma.setShortCode("ROUTE" + FIRMAS.incrementAndGet());
        firma.setCountry("PL");
        firma.setTimezone("UTC");
        firma.setBaseCurrency("PLN");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-route@test.local", "RouteOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "RouteOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    @Test
    void placesAreSuggestedWhileTyping() throws Exception {
        Client c = newFirmaOwner();
        assertThat(body(c.get("/app/calc/places?q=Wr")).size()).as("under 3 characters").isZero();
        int before = PHOTON_CALLS.get();
        JsonNode places = body(c.get("/app/calc/places?q=Wroc"));
        assertThat(places.size()).isEqualTo(2);
        assertThat(places.get(0).get("label").asString()).isEqualTo("Wrocław, województwo dolnośląskie, Polska");
        assertThat(places.get(0).get("lat").asDouble()).isEqualTo(51.11);
        assertThat(places.get(0).get("lng").asDouble()).isEqualTo(17.0333);
        assertThat(places.get(0).get("country").asString()).isEqualTo("PL");
        assertThat(places.get(1).get("label").asString()).isEqualTo("Wrocławska 12, 55-100 Trzebnica, Polska");
        assertThat(places.get(1).get("country").asString()).isEqualTo("PL");
        assertThat(body(c.get("/app/calc/places?q=wroc")).size()).as("cached, case-insensitive").isEqualTo(2);
        assertThat(PHOTON_CALLS.get() - before).isEqualTo(1);
        c.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/app/calc/places")
                .param("q", "Łódź Piotrkowska"));
        assertThat(lastRawQuery).as("encoded once").contains("q=%C5%81%C3%B3d%C5%BA%20Piotrkowska")
                .doesNotContain("%25");
        MvcResult broken = c.get("/app/calc/places?q=Broken");
        assertThat(broken.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(broken).get("code").asString()).isEqualTo("calc.geocode.failed");
    }

    @Test
    void routesGeocodeAndFeedTheCalculator() throws Exception {
        Client c = newFirmaOwner();
        MvcResult v = c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "RT 1001", "heightMm", 4000,
                "grossWeightKg", 40000, "axles", 5));
        assertThat(v.getResponse().getStatus()).as(v.getResponse().getContentAsString()).isEqualTo(201);
        long vehicleId = body(v).get("id").asLong();

        Map<String, Object> request = new HashMap<>();
        request.put("points", List.of(Map.of("address", "Poznań"), Map.of("lat", 52.52, "lng", 13.405)));
        request.put("vehicleId", vehicleId);
        request.put("avoidFerries", true);
        MvcResult res = c.post("/app/calc/route", request);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode route = body(res);
        assertThat(route.get("distanceKm").asDouble()).isEqualTo(280.0);
        assertThat(route.get("durationMin").asInt()).isEqualTo(200);
        assertThat(route.get("polylinePrecision").asInt()).isEqualTo(6);
        assertThat(route.get("attribution").asString()).contains("OpenStreetMap");
        assertThat(route.get("points").get(0).get("label").asString()).isEqualTo("Poznań, Polska");
        JsonNode leg = route.get("legs").get(0);
        assertThat(leg.get("polyline").asString()).isEqualTo("_p~iF~ps|U_ulLnnqC");
        assertThat(leg.get("countries").get(0).get("country").asString()).isEqualTo("PL");
        assertThat(leg.get("countries").get(1).get("km").asDouble()).isEqualTo(130.0);
        assertThat(leg.get("countries").get(0).get("km").asDouble()).isEqualTo(150.0);
        assertThat(leg.get("countries").get(0).get("tollKm").asDouble()).as("PL: motorway only").isEqualTo(100.0);
        assertThat(leg.get("countries").get(1).get("tollKm").asDouble()).as("DE: federal roads too").isEqualTo(130.0);
        assertThat(lastRoute).contains("\"costing\":\"truck\"").contains("\"height\":4.0").contains("\"weight\":40.0")
                .contains("\"axle_count\":5").contains("\"use_ferry\":0.0");
        assertThat(route.get("truck").get("weightT").asDouble()).isEqualTo(40.0);
        JsonNode breakdown = route.get("breakdown");
        assertThat(breakdown.get("days").asInt()).isEqualTo(1);
        assertThat(breakdown.get("eta").asString()).isNotEmpty();
        assertThat(breakdown.get("countries").get(0).get("tollKm").asDouble()).isEqualTo(100.0);
        assertThat(breakdown.get("costs").get("missing").toString()).contains("calc.missing.toll_tariff")
                .contains("calc.missing.consumption");

        // With a tariff the route says what the tolls cost: toll km x rate per country.
        assertThat(c.post("/app/calc/toll-tariffs", Map.of("country", "DE", "ratePerKm", 0.348, "currency", "EUR"))
                .getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> costed = new HashMap<>(request);
        costed.put("start", "2026-11-02T06:00:00Z");
        JsonNode lines = body(c.post("/app/calc/route", costed)).get("breakdown").get("costs").get("lines");
        JsonNode deToll = null;
        for (JsonNode l : lines) {
            if (l.get("kind").asString().equals("TOLL") && l.get("country").asString().equals("DE")) {
                deToll = l;
            }
        }
        assertThat(deToll).isNotNull();
        assertThat(deToll.get("quantity").asDouble()).isEqualTo(130.0);
        assertThat(deToll.get("amount").asDouble()).isEqualTo(45.24);

        // A tractor drives as a combination: 40 t whatever its card says, its trailer's axles and height added.
        long trailerId = body(c.post("/app/fleet/vehicles", Map.of("kind", "SEMI_TRAILER", "plate", "RT 1002",
                "heightMm", 4050, "axles", 3))).get("id").asLong();
        long tractorId = body(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "RT 1003",
                "heightMm", 3800, "grossWeightKg", 18000, "axles", 2, "defaultTrailerId", trailerId))).get("id").asLong();
        JsonNode combination = body(c.post("/app/calc/route", Map.of("vehicleId", tractorId, "points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.52, "lng", 13.405))))).get("truck");
        assertThat(combination.get("weightT").asDouble()).isEqualTo(40.0);
        assertThat(combination.get("axles").asInt()).isEqualTo(5);
        assertThat(combination.get("heightM").asDouble()).isEqualTo(4.05);
        assertThat(combination.get("lengthM").asDouble()).isEqualTo(16.5);
        assertThat(lastRoute).contains("\"weight\":40.0").contains("\"axle_count\":5").contains("\"height\":4.05")
                .contains("\"width\":2.55").contains("\"length\":16.5").contains("\"axle_load\":11.5");

        // A tractor with an empty card and no trailer: the combination limits.
        long bareId = body(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "RT 1005"))).get("id")
                .asLong();
        MvcResult bare = c.post("/app/calc/route", Map.of("vehicleId", bareId, "points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.52, "lng", 13.405))));
        assertThat(bare.getResponse().getStatus()).as(bare.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(bare).get("truck").get("heightM").asDouble()).isEqualTo(4.0);

        // A van with an empty card gets the van limits; no vehicle means a combination; the request overrides.
        long vanId = body(c.post("/app/fleet/vehicles", Map.of("kind", "VAN", "plate", "RT 1004"))).get("id").asLong();
        JsonNode van = body(c.post("/app/calc/route", Map.of("vehicleId", vanId, "points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.52, "lng", 13.405))))).get("truck");
        assertThat(van.get("weightT").asDouble()).isEqualTo(3.5);
        assertThat(van.get("heightM").asDouble()).isEqualTo(2.7);
        assertThat(van.get("axleLoadT").isNull()).isTrue();
        assertThat(lastRoute).doesNotContain("axle_load");
        JsonNode none = body(c.post("/app/calc/route", Map.of("points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.52, "lng", 13.405))))).get("truck");
        assertThat(none.get("weightT").asDouble()).isEqualTo(40.0);
        assertThat(none.get("axles").asInt()).isEqualTo(5);
        JsonNode light = body(c.post("/app/calc/route", Map.of("vehicleId", tractorId, "profile",
                Map.of("weightT", 24.0, "hazmat", true), "points",
                List.of(Map.of("lat", 52.4, "lng", 16.9), Map.of("lat", 52.52, "lng", 13.405))))).get("truck");
        assertThat(light.get("weightT").asDouble()).isEqualTo(24.0);
        assertThat(light.get("hazmat").asBoolean()).isTrue();
        assertThat(lastRoute).contains("\"weight\":24.0").contains("\"hazmat\":true");

        // A Polish letter and spaces reach the geocoder encoded once (regression: they were encoded twice).
        MvcResult polish = c.post("/app/calc/route", Map.of("points",
                List.of(Map.of("address", "Wrocław, wagnera 3"), Map.of("lat", 52.0, "lng", 13.0))));
        assertThat(polish.getResponse().getStatus()).as(polish.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(polish).get("points").get(0).get("lat").asDouble()).isEqualTo(51.052);
        assertThat(lastRawQuery).contains("q=Wroc%C5%82aw,%20wagnera%203").doesNotContain("%25");

        MvcResult unknown = c.post("/app/calc/route", Map.of("points",
                List.of(Map.of("address", "Nowhere 1"), Map.of("lat", 52.0, "lng", 13.0))));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(unknown).get("details").get("points[0]").asString()).isEqualTo("calc.geocode.not_found");
        MvcResult noRoute = c.post("/app/calc/route", Map.of("points",
                List.of(Map.of("lat", 0.0, "lng", 0.0), Map.of("lat", 52.0, "lng", 13.0))));
        assertThat(body(noRoute).get("details").get("route").asString()).isEqualTo("calc.route.not_found");
        MvcResult onePoint = c.post("/app/calc/route", Map.of("points", List.of(Map.of("lat", 52.0, "lng", 13.0))));
        assertThat(body(onePoint).get("details").get("points").asString()).isEqualTo("validation.invalid_value");

        Map<String, Object> trip = new HashMap<>();
        trip.put("start", "2026-11-02T06:00:00Z");
        trip.put("vehicleId", vehicleId);
        trip.put("waypoints", List.of(Map.of("address", "Poznań", "serviceMin", 60),
                Map.of("lat", 52.52, "lng", 13.405, "serviceMin", 30)));
        MvcResult plan = c.post("/app/calc/trip", trip);
        assertThat(plan.getResponse().getStatus()).as(plan.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode p = body(plan);
        assertThat(p.get("totals").get("distanceKm").asDouble()).isEqualTo(280.0);
        assertThat(p.get("totals").get("drivingMin").asInt()).isEqualTo(200);
        assertThat(p.get("totals").get("workMin").asInt()).as("loading and unloading").isEqualTo(90);
        Map<String, Double> tollKm = new HashMap<>();
        for (JsonNode l : p.get("costs").get("lines")) {
            if (l.get("kind").asString().equals("TOLL")) {
                tollKm.put(l.get("country").asString(), l.get("quantity").asDouble());
            }
        }
        assertThat(tollKm).as("toll km per country from the route").containsEntry("DE", 130.0).containsEntry("PL", 100.0);
    }
}
