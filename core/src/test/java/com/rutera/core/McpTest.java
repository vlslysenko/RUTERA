package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP server on a real port (the tools call the REST API over the loopback interface): initialize, tools from
 * the OpenAPI document with names, schemas and value hints, calls with the token's privileges, protocol errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTest extends AbstractIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private AuditLogRepository auditLog;

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        firma = new Firma();
        firma.setName("Mcp Test");
        firma.setShortCode("MCP1");
        firma.setCountry("PL");
        firma.setAiAccess(true);
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-mcp@test.local", "McpOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "McpOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private String token(Client c) throws Exception {
        MvcResult res = c.post("/app/me/tokens", Map.of("name", "Assistant"));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("token").asString();
    }

    private HttpResponse<String> mcp(String token, Object message) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String token, int id, String method, Object params) throws Exception {
        HttpResponse<String> res = mcp(token, Map.of("jsonrpc", "2.0", "id", id, "method", method,
                "params", params));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private JsonNode call(String token, String tool, Object arguments) throws Exception {
        JsonNode answer = rpc(token, 9, "tools/call", Map.of("name", tool, "arguments", arguments));
        assertThat(answer.has("result")).as(answer.toString()).isTrue();
        return answer.get("result");
    }

    @Test
    void anAssistantListsAndCallsTools() throws Exception {
        Client c = newFirmaOwner();
        String token = token(c);

        assertThat(mcp(null, Map.of("jsonrpc", "2.0", "id", 1, "method", "ping")).statusCode()).isEqualTo(401);
        JsonNode init = rpc(token, 1, "initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
        assertThat(init.get("result").get("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.get("result").get("serverInfo").get("name").asString()).isEqualTo("rutera");
        assertThat(init.get("result").get("capabilities").has("tools")).isTrue();
        assertThat(init.get("result").get("capabilities").has("resources")).isTrue();
        assertThat(init.get("result").get("instructions").asString()).contains("rutera://guide");
        assertThat(mcp(token, Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode())
                .isEqualTo(202);

        JsonNode list = rpc(token, 2, "tools/list", Map.of()).get("result").get("tools");
        List<String> names = new ArrayList<>();
        list.forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).contains("vehicles_list", "vehicles_get", "vehicles_create", "cmr_issue", "cmr_from_order",
                "orders_create_trip", "calc_trip", "invoices_list", "fuel_transactions_assign", "tacho_violations_ack",
                "tacho_violations_unack", "drivers_absences_create", "reminders_schedule_get", "debts_list",
                "calc_route", "drivers_absences_decide", "trips_files_list", "trips_files_delete", "audit_list");
        assertThat(names).doesNotContain("cmr_pdf_list", "tacho_files_create", "fuel_imports_create",
                "trips_files_create", "trips_files_content_list", "calc_route_create");
        assertThat(find(list, "drivers_absences_decide").get("description").asString())
                .contains("REQUESTED | APPROVED | REJECTED");
        JsonNode get = find(list, "vehicles_get");
        assertThat(get.get("inputSchema").get("required").toString()).contains("id");
        assertThat(get.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(find(list, "invoices_list").get("description").asString()).contains("OPEN | PARTIAL | PAID");
        assertThat(find(list, "vehicles_create").get("inputSchema").get("properties").get("body")
                .get("properties").has("plate")).isTrue();

        JsonNode created = call(token, "vehicles_create", Map.of("body", Map.of("kind", "TRACTOR", "plate", "MCP 001")));
        assertThat(created.get("isError").asBoolean()).as(created.toString()).isFalse();
        assertThat(created.get("structuredContent").get("plate").asString()).isEqualTo("MCP 001");
        long vehicleId = created.get("structuredContent").get("id").asLong();
        AuditLog entry = auditLog.findByFirmaIdOrderByAtDesc(firma.getId(), PageRequest.of(0, 20)).stream()
                .filter(a -> "vehicle_created".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(entry.getChannel()).as("an action of a tool is marked as MCP").isEqualTo(AuditLog.Channel.MCP);
        JsonNode one = call(token, "vehicles_get", Map.of("id", vehicleId));
        assertThat(one.get("content").get(0).get("text").asString()).contains("MCP 001");
        JsonNode missing = call(token, "vehicles_get", Map.of("id", 999_999));
        assertThat(missing.get("isError").asBoolean()).isTrue();
        assertThat(missing.get("structuredContent").get("code").asString()).isEqualTo("error.not_found");
        JsonNode invalid = call(token, "vehicles_create", Map.of("body", Map.of("kind", "TRACTOR")));
        assertThat(invalid.get("isError").asBoolean()).isTrue();
        assertThat(invalid.get("structuredContent").get("details").has("plate")).isTrue();

        JsonNode resources = rpc(token, 4, "resources/list", Map.of()).get("result").get("resources");
        List<String> uris = new ArrayList<>();
        resources.forEach(r -> uris.add(r.get("uri").asString()));
        assertThat(uris).startsWith("rutera://guide").contains("rutera://guides/readme", "rutera://guides/owner",
                "rutera://guides/dispatcher", "rutera://guides/logistician", "rutera://guides/accountant",
                "rutera://guides/driver");
        assertThat(resources.get(0).get("mimeType").asString()).isEqualTo("text/markdown");
        JsonNode guide = rpc(token, 5, "resources/read", Map.of("uri", "rutera://guide")).get("result");
        assertThat(guide.get("contents").get(0).get("text").asString())
                .as("the owner gets the owner guide").contains("role \"Owner\"").contains("debts_list");
        JsonNode driverGuide = rpc(token, 6, "resources/read", Map.of("uri", "rutera://guides/driver"));
        assertThat(driverGuide.get("result").get("contents").get(0).get("text").asString())
                .contains("role \"Driver\"");
        assertThat(rpc(token, 7, "resources/read", Map.of("uri", "rutera://guides/nobody")).get("error").get("code")
                .asInt()).isEqualTo(-32002);
        assertThat(rpc(token, 8, "resources/templates/list", Map.of()).get("result").get("resourceTemplates")
                .isEmpty()).isTrue();

        JsonNode noId = rpc(token, 3, "tools/call", Map.of("name", "vehicles_get", "arguments", Map.of()));
        assertThat(noId.get("error").get("code").asInt()).isEqualTo(-32602);
        JsonNode unknown = rpc(token, 4, "prompts/list", Map.of());
        assertThat(unknown.get("error").get("code").asInt()).isEqualTo(-32601);

        AppUser dispatcher = createUser("dispatcher-mcp@test.local", "McpDisp-1", Role.DISPATCHER, firma.getId(),
                true);
        Client d = client();
        assertThat(d.login(dispatcher.getLogin(), "McpDisp-1").getResponse().getStatus()).isEqualTo(200);
        String dispatcherToken = token(d);
        JsonNode denied = call(dispatcherToken, "invoices_list", Map.of());
        assertThat(rpc(dispatcherToken, 10, "resources/read", Map.of("uri", "rutera://guide")).get("result")
                .get("contents").get(0).get("text").asString()).as("each role gets its own guide")
                .contains("role \"Dispatcher\"");
        assertThat(denied.get("isError").asBoolean()).isTrue();
        assertThat(denied.get("structuredContent").get("code").asString()).isEqualTo("auth.access_denied");

        long tokenId = body(c.get("/app/me/tokens")).get(0).get("id").asLong();
        JsonNode calls = body(c.get("/app/me/tokens/" + tokenId + "/calls?size=100"));
        List<String> paths = new ArrayList<>();
        calls.get("items").forEach(i -> paths.add(i.get("path").asString()));
        assertThat(paths).contains("/mcp", "/api/v1/vehicles");
    }

    private static JsonNode find(JsonNode tools, String name) {
        for (JsonNode t : tools) {
            if (t.get("name").asString().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("no tool " + name);
    }
}
