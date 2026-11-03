package com.rutera.core.mcp;

import com.rutera.common.domain.CmrDocument;
import com.rutera.common.domain.DriverAbsence;
import com.rutera.common.domain.DriverDay;
import com.rutera.common.domain.DriverPayment;
import com.rutera.common.domain.DriverViolation;
import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.domain.Invoice;
import com.rutera.common.domain.InvoiceLine;
import com.rutera.common.domain.InvoicePayment;
import com.rutera.common.domain.TransportOrder;
import com.rutera.common.domain.Trip;
import com.rutera.common.domain.TripFile;
import com.rutera.common.domain.TripStop;
import com.rutera.common.domain.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MCP tools: one per operation of the public API, read from its OpenAPI document ({@code /api/v1/openapi.json}),
 * so new endpoints become tools without code. A call runs the same /api/v1 request over the loopback interface
 * with the caller's own token: validation, privileges, answers and the call log are the ones of the REST API.
 * Binary and multipart operations (PDF, file content, uploads) are left out.
 */
@Component
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);
    private static final String PREFIX = "/api/v1/";
    private static final Pattern SKIP = Pattern.compile(".*/(pdf|content)$");
    private static final Set<String> ACTIONS = Set.of("issue", "cancel", "ack", "trip", "driver", "vehicle",
            "from_order", "from_trip", "route");
    private static final Map<String, String> NAMES = Map.of(
            "POST /api/v1/orders/{id}/trip", "orders_create_trip",
            "GET /api/v1/reminders/schedule", "reminders_schedule_get",
            "PATCH /api/v1/fuel/transactions/{id}", "fuel_transactions_assign",
            "GET /api/v1/tacho/activity", "tacho_activity_get",
            "DELETE /api/v1/tacho/violations/{id}/ack", "tacho_violations_unack",
            "PATCH /api/v1/drivers/{id}/absences/{absenceId}", "drivers_absences_decide");
    private static final Map<String, String> ACTION_TEXT = Map.ofEntries(
            Map.entry("orders_create_trip", "Create a trip for this order in one step (vehicle, optional trailer, "
                    + "drivers, planned start); the order's loading and unloading become the stops."),
            Map.entry("cmr_from_order", "Create a draft CMR note prefilled from an order (sender = customer, "
                    + "carrier = own company, places, cargo, vehicle plates)."),
            Map.entry("cmr_from_trip", "Create draft CMR notes for a trip: one per order of the trip."),
            Map.entry("cmr_issue", "Issue a draft CMR note: freezes the number and stores the PDF. Fails with the "
                    + "missing boxes in details."),
            Map.entry("cmr_cancel", "Cancel a CMR note."),
            Map.entry("calc_trip", "Plan a trip under EU 561/2006: breaks, daily and weekly rests, arrival time and "
                    + "cost estimate (fuel, driver pay, tolls) for the given legs, vehicle and drivers."),
            Map.entry("calc_route", "Compute a truck route through 2 to 25 points (coordinates or addresses, which "
                    + "are geocoded): distance, duration, km per country and the legs for calc_trip. Needs the "
                    + "routing server of the installation (else error calc.routing.disabled)."),
            Map.entry("drivers_absences_decide", "Approve or reject an absence requested by a driver (status "
                    + "APPROVED or REJECTED, optional note)."),
            Map.entry("tacho_violations_ack", "Acknowledge a driving-time violation (with an optional note)."),
            Map.entry("tacho_violations_unack", "Remove the acknowledgement of a driving-time violation."),
            Map.entry("tacho_files_driver", "Create a driver from the card holder of a tachograph card file and "
                    + "assign the file."),
            Map.entry("tacho_files_vehicle", "Create a vehicle from a tachograph vehicle unit file and assign the "
                    + "file."),
            Map.entry("invoices_reminders_create", "Send a payment reminder for an open sales invoice now (queued "
                    + "e-mail to the contractor's invoice addresses)."));

    /**
     * Allowed values of the coded fields per resource (tool name prefix, longest first), appended to the tool
     * descriptions because the assistant cannot see the translations of the codes.
     */
    private static final Map<String, String> VALUES = Map.ofEntries(
            Map.entry("invoices", "status = " + values(Invoice.Status.values()) + "; direction = "
                    + values(Invoice.Direction.values()) + "; lines[].vatCode = " + values(InvoiceLine.VatCode.values())
                    + " (vatRate in percent only for RATE); payment method = " + values(InvoicePayment.Method.values())),
            Map.entry("debts", "amounts per contractor and currency; buckets notDue, d1to30, d31to60, d61to90, over90"),
            Map.entry("reminders", "steps[].offsetDays = days from the due date (negative = before)"),
            Map.entry("cmr", "status = " + values(CmrDocument.Status.values()) + "; paymentTerms = "
                    + values(CmrDocument.PaymentTerms.values())),
            Map.entry("orders", "status = " + values(TransportOrder.Status.values())),
            Map.entry("trips", "status = " + values(Trip.Status.values()) + "; stops[].kind = "
                    + values(TripStop.Kind.values()) + "; stops[].status = " + values(TripStop.Status.values())),
            Map.entry("fuel_transactions", "product = " + values(FuelTransaction.Product.values()) + "; unit = "
                    + values(FuelTransaction.Unit.values()) + "; match = " + values(FuelTransaction.Match.values())
                    + "; source = " + values(FuelTransaction.Source.values())),
            Map.entry("fuel", "provider = " + values(FuelProvider.values())),
            Map.entry("vehicles", "kind = " + values(Vehicle.Kind.values()) + "; status = "
                    + values(Vehicle.Status.values()) + "; euroClass = " + values(Vehicle.EuroClass.values())),
            Map.entry("drivers_absences", "kind = " + values(DriverAbsence.Kind.values()) + "; status = "
                    + values(DriverAbsence.Status.values())),
            Map.entry("absences", "kind = " + values(DriverAbsence.Kind.values()) + "; status = "
                    + values(DriverAbsence.Status.values())),
            Map.entry("trips_files", "kind = " + values(TripFile.Kind.values())
                    + "; uploads and file contents are not tools (multipart and binary)"),
            Map.entry("drivers_payments", "kind = " + values(DriverPayment.Kind.values())),
            Map.entry("payments", "kind = " + values(DriverPayment.Kind.values())),
            Map.entry("drivers_days", "kind = " + values(DriverDay.Kind.values())),
            Map.entry("calc", "driverState = TACHO | FRESH | MANUAL; euroClass of tariffs = "
                    + values(Vehicle.EuroClass.values()) + "; times are ISO instants"),
            Map.entry("tacho", "severity = " + values(DriverViolation.Severity.values()) + "; rule = "
                    + values(DriverViolation.Rule.values())));

    private static String values(Enum<?>[] values) {
        StringBuilder out = new StringBuilder();
        for (Enum<?> v : values) {
            out.append(out.isEmpty() ? "" : " | ").append(v.name());
        }
        return out.toString();
    }

    /** A tool and the REST operation behind it. */
    public record Tool(String name, String description, String method, String path, ObjectNode inputSchema,
                       List<String> pathParams, List<String> queryParams, boolean hasBody) {
    }

    /** What a call answered: HTTP status and body. */
    public record Result(int status, String body) {
    }

    private final ObjectMapper json;
    private final Environment environment;
    private volatile List<Tool> tools;
    private volatile RestClient client;

    public McpTools(ObjectMapper json, Environment environment) {
        this.json = json;
        this.environment = environment;
    }

    /** The tools, built from the OpenAPI document on first use. */
    public List<Tool> tools() {
        List<Tool> t = tools;
        if (t == null) {
            synchronized (this) {
                if (tools == null) {
                    tools = build(json.readTree(client().get().uri("/api/v1/openapi.json")
                            .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class)));
                }
                t = tools;
            }
        }
        return t;
    }

    public Tool tool(String name) {
        return tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    /** Runs the tool's REST operation with the caller's Authorization header; never throws for 4xx/5xx. */
    public Result call(Tool tool, JsonNode arguments, String authorization) {
        String path = tool.path();
        for (String p : tool.pathParams()) {
            JsonNode v = arguments.get(p);
            if (v == null || v.isNull() || v.asString().isBlank()) {
                throw new IllegalArgumentException("Missing argument: " + p);
            }
            path = path.replace("{" + p + "}", UriUtils.encodePathSegment(v.asString(), StandardCharsets.UTF_8));
        }
        StringBuilder query = new StringBuilder();
        for (String q : tool.queryParams()) {
            JsonNode v = arguments.get(q);
            if (v != null && !v.isNull()) {
                query.append(query.isEmpty() ? '?' : '&').append(UriUtils.encodeQueryParam(q, StandardCharsets.UTF_8))
                        .append('=').append(UriUtils.encodeQueryParam(v.asString(), StandardCharsets.UTF_8));
            }
        }
        RestClient.RequestBodySpec request = client().method(HttpMethod.valueOf(tool.method()))
                .uri(path + query).header(HttpHeaders.AUTHORIZATION, authorization)
                .accept(MediaType.APPLICATION_JSON);
        JsonNode body = arguments.get("body");
        if (tool.hasBody() && body != null && !body.isNull()) {
            request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        ResponseEntity<String> response = request.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
        }).toEntity(String.class);
        return new Result(response.getStatusCode().value(), response.getBody());
    }

    // --- building the tools --------------------------------------------------------------------------------

    List<Tool> build(JsonNode doc) {
        List<Tool> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        JsonNode components = doc.path("components").path("schemas");
        for (Map.Entry<String, JsonNode> p : doc.path("paths").properties()) {
            String path = p.getKey();
            if (!path.startsWith(PREFIX) || SKIP.matcher(path).matches() || path.endsWith("/openapi.json")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : p.getValue().properties()) {
                String method = op.getKey().toUpperCase(Locale.ROOT);
                if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                    continue;
                }
                JsonNode content = op.getValue().path("requestBody").path("content");
                if (content.has("multipart/form-data")) {
                    continue;
                }
                String name = name(method, path);
                while (!used.add(name)) {
                    name = name + "_2";
                }
                ObjectNode schema = json.createObjectNode();
                schema.put("type", "object");
                ObjectNode props = schema.putObject("properties");
                ArrayNode required = json.createArrayNode();
                List<String> pathParams = new ArrayList<>();
                List<String> queryParams = new ArrayList<>();
                for (JsonNode param : op.getValue().path("parameters")) {
                    String in = param.path("in").asString();
                    String pname = param.path("name").asString();
                    if (!in.equals("path") && !in.equals("query")) {
                        continue;
                    }
                    JsonNode s = resolve(param.path("schema"), components, new HashSet<>(), 0);
                    ObjectNode prop = s.isObject() ? (ObjectNode) s.deepCopy() : json.createObjectNode();
                    if (param.has("description")) {
                        prop.put("description", param.path("description").asString());
                    }
                    props.set(pname, prop);
                    if (in.equals("path")) {
                        pathParams.add(pname);
                        required.add(pname);
                    } else {
                        queryParams.add(pname);
                        if (param.path("required").asBoolean(false)) {
                            required.add(pname);
                        }
                    }
                }
                boolean hasBody = content.has("application/json");
                if (hasBody) {
                    JsonNode s = resolve(content.path("application/json").path("schema"), components,
                            new HashSet<>(), 0);
                    ObjectNode body = s.isObject() ? (ObjectNode) s.deepCopy() : json.createObjectNode();
                    body.put("description", "The request body (JSON), as in the REST API.");
                    props.set("body", body);
                    if (op.getValue().path("requestBody").path("required").asBoolean(false)) {
                        required.add("body");
                    }
                }
                if (!required.isEmpty()) {
                    schema.set("required", required);
                }
                result.add(new Tool(name, description(name, method, path), method, path, schema,
                        List.copyOf(pathParams), List.copyOf(queryParams), hasBody));
            }
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        log.info("MCP: {} tools from the OpenAPI document", result.size());
        return List.copyOf(result);
    }

    /** vehicles_list, vehicles_get, cmr_issue, drivers_absences_create, ... (see the class comment). */
    static String name(String method, String path) {
        String override = NAMES.get(method + " " + path);
        if (override != null) {
            return override;
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        List<String> literals = new ArrayList<>();
        for (String s : segments) {
            if (!s.startsWith("{")) {
                literals.add(s.replace('-', '_'));
            }
        }
        boolean endsWithParam = segments[segments.length - 1].startsWith("{");
        String base = String.join("_", literals);
        String last = literals.getLast();
        String verb = switch (method) {
            case "GET" -> endsWithParam ? "get" : "list";
            case "POST" -> ACTIONS.contains(last) ? "" : "create";
            case "PUT" -> "update";
            case "PATCH" -> "patch";
            default -> "delete";
        };
        return verb.isEmpty() ? base : base + "_" + verb;
    }

    private static String description(String name, String method, String path) {
        String operation = " (" + method + " " + path + ")";
        String action = ACTION_TEXT.get(name);
        if (action != null) {
            return action + operation + ". Same privileges as in the RUTERA web application." + hint(name);
        }
        String label = name.replaceAll("_(list|get|create|update|patch|delete|assign)$", "").replace('_', ' ');
        String text = switch (method) {
            case "GET" -> path.endsWith("}") ? "Get one record of " + label + " by its id"
                    : "List " + label + "; query parameters filter and page the result";
            case "POST" -> "Create a record of " + label + " from the body";
            case "PUT" -> "Replace a record of " + label + " (full replacement: send every field of the body)";
            case "PATCH" -> "Change part of a record of " + label;
            default -> "Delete a record of " + label;
        };
        return text + operation + ". Same privileges as in the RUTERA web application." + hint(name);
    }

    /** " Values: ..." for the longest matching resource prefix of the tool name, or "". */
    private static String hint(String name) {
        String best = null;
        for (String prefix : VALUES.keySet()) {
            if ((name.equals(prefix) || name.startsWith(prefix + "_")) && (best == null
                    || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? "" : " Values: " + VALUES.get(best) + ".";
    }

    /** The schema with every $ref to components replaced by its target (cycles and deep nesting cut off). */
    private JsonNode resolve(JsonNode schema, JsonNode components, Set<String> seen, int depth) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return json.createObjectNode();
        }
        if (schema.isArray()) {
            ArrayNode out = json.createArrayNode();
            for (JsonNode item : schema) {
                out.add(resolve(item, components, seen, depth));
            }
            return out;
        }
        if (!schema.isObject()) {
            return schema;
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asString();
            String key = ref.substring(ref.lastIndexOf('/') + 1);
            if (seen.contains(key) || depth > 8 || !components.has(key)) {
                ObjectNode any = json.createObjectNode();
                any.put("type", "object");
                return any;
            }
            Set<String> next = new HashSet<>(seen);
            next.add(key);
            return resolve(components.get(key), components, next, depth + 1);
        }
        ObjectNode out = json.createObjectNode();
        for (Map.Entry<String, JsonNode> e : schema.properties()) {
            out.set(e.getKey(), resolve(e.getValue(), components, seen, depth + 1));
        }
        return out;
    }

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            String port = environment.getProperty("local.server.port");
            if (port == null) {
                throw new IllegalStateException("The web server port is not known yet");
            }
            c = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                    .baseUrl("http://127.0.0.1:" + port).build();
            client = c;
        }
        return c;
    }

    /** For the tools/list answer: name, description, inputSchema and annotations of each tool. */
    public ArrayNode describe() {
        ArrayNode list = json.createArrayNode();
        for (Tool t : tools()) {
            ObjectNode node = list.addObject();
            node.put("name", t.name());
            node.put("description", t.description());
            node.set("inputSchema", t.inputSchema());
            ObjectNode hints = node.putObject("annotations");
            hints.put("readOnlyHint", t.method().equals("GET"));
            hints.put("destructiveHint", t.method().equals("DELETE"));
            hints.put("idempotentHint", !t.method().equals("POST") && !t.method().equals("PATCH"));
        }
        return list;
    }
}
