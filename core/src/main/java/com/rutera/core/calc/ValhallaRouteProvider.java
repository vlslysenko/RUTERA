package com.rutera.core.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Truck routes from a Valhalla server (MIT licence; OSM data, ODbL): {@code /route} with the truck costing gives the
 * legs (length, time, shape with precision 6), {@code /trace_attributes} on each leg's shape gives the km per
 * country (edge length by the country of its end node). Countries stay empty when that second call fails.
 */
public class ValhallaRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(ValhallaRouteProvider.class);

    private final RestClient client;
    private final ObjectMapper json;

    public ValhallaRouteProvider(String baseUrl, Duration timeout, ObjectMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl.replaceAll("/+$", "")).build();
        this.json = json;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Leg> route(List<Point> points, Profile profile) {
        ObjectNode request = json.createObjectNode();
        ArrayNode locations = request.putArray("locations");
        for (Point p : points) {
            locations.addObject().put("lat", p.lat()).put("lon", p.lng());
        }
        request.put("costing", "truck");
        ObjectNode truck = request.putObject("costing_options").putObject("truck");
        put(truck, "height", profile.heightM());
        put(truck, "width", profile.widthM());
        put(truck, "length", profile.lengthM());
        put(truck, "weight", profile.weightT());
        put(truck, "axle_load", profile.axleLoadT());
        if (profile.axles() != null) {
            truck.put("axle_count", profile.axles());
        }
        truck.put("hazmat", profile.hazmat());
        truck.put("use_tolls", profile.avoidTolls() ? 0.0 : 0.5);
        truck.put("use_ferry", profile.avoidFerries() ? 0.0 : 0.5);
        request.putObject("directions_options").put("units", "kilometers");
        request.put("directions_type", "none");

        JsonNode answer = post("/route", request);
        if (answer.has("error_code") || !answer.has("trip")) {
            throw new NotFound(answer.path("error").asString("no route"));
        }
        List<Leg> legs = new ArrayList<>();
        for (JsonNode leg : answer.path("trip").path("legs")) {
            double km = leg.path("summary").path("length").asDouble();
            int minutes = (int) Math.round(leg.path("summary").path("time").asDouble() / 60.0);
            String shape = leg.path("shape").asString("");
            legs.add(new Leg(km, minutes, shape, countries(shape)));
        }
        if (legs.size() != points.size() - 1) {
            throw new Failed("unexpected number of legs");
        }
        return legs;
    }

    /** km per ISO country code along the shape; empty when Valhalla cannot tell. */
    private Map<String, Double> countries(String shape) {
        Map<String, Double> km = new LinkedHashMap<>();
        if (shape.isEmpty()) {
            return km;
        }
        ObjectNode request = json.createObjectNode();
        request.put("encoded_polyline", shape);
        request.put("costing", "truck");
        request.put("shape_match", "edge_walk");
        ObjectNode filters = request.putObject("filters");
        filters.putArray("attributes").add("edge.length").add("node.admin_index").add("admin.country_code");
        filters.put("action", "include");
        request.putObject("directions_options").put("units", "kilometers");
        try {
            JsonNode answer = post("/trace_attributes", request);
            JsonNode admins = answer.path("admins");
            for (JsonNode edge : answer.path("edges")) {
                int index = edge.path("end_node").path("admin_index").asInt(-1);
                String code = index >= 0 && index < admins.size()
                        ? admins.get(index).path("country_code").asString("") : "";
                if (code.length() == 2) {
                    km.merge(code, edge.path("length").asDouble(), Double::sum);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Valhalla trace_attributes failed, no countries: {}", e.toString());
        }
        return km;
    }

    private JsonNode post(String path, ObjectNode body) {
        try {
            ResponseEntity<String> res = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, r) -> {
                    }).toEntity(String.class);
            if (res.getStatusCode().is5xxServerError() || res.getBody() == null) {
                throw new Failed("Valhalla answered " + res.getStatusCode().value());
            }
            return json.readTree(res.getBody());
        } catch (RestClientException | JacksonException e) {
            throw new Failed(e.getMessage());
        }
    }

    private static void put(ObjectNode node, String name, Double value) {
        if (value != null) {
            node.put(name, value);
        }
    }
}
