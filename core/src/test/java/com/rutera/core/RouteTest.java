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
            send(ex, 200, "{\"edges\":[{\"length\":150.0,\"end_node\":{\"admin_index\":0}},"
                    + "{\"length\":130.0,\"end_node\":{\"admin_index\":1}}],"
                    + "\"admins\":[{\"country_code\":\"PL\"},{\"country_code\":\"DE\"}]}");
        });
        STUB.createContext("/nominatim/search", ex -> {
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (query.contains("Nowhere")) {
                send(ex, 200, "[]");
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
    }

    private Client newFirmaOwner() throws Exception {
        Firma firma = new Firma();
        firma.setName("Route Test");
        firma.setShortCode("ROUTE1");
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
        assertThat(lastRoute).contains("\"costing\":\"truck\"").contains("\"height\":4.0").contains("\"weight\":40.0")
                .contains("\"axle_count\":5").contains("\"use_ferry\":0.0");

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
        boolean germanToll = false;
        for (JsonNode l : p.get("costs").get("lines")) {
            if (l.get("kind").asString().equals("TOLL") && l.get("country").asString().equals("DE")) {
                germanToll = l.get("quantity").asDouble() == 130.0;
            }
        }
        assertThat(germanToll).as("km per country from the route").isTrue();
    }
}
