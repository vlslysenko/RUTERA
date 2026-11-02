package com.rutera.core.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * MCP server for AI assistants (Streamable HTTP transport of the Model Context Protocol, without a server-initiated
 * stream): {@code POST /mcp} takes JSON-RPC messages (one or a batch) and answers JSON. Methods: initialize, ping,
 * tools/list, tools/call and the initialized notification. Authentication, the AI access add-on, the rate limit and
 * the call log are the ones of the public API (bearer token, {@code ApiTokenFilter}); the tools are the /api/v1
 * operations ({@link McpTools}).
 */
@RestController
public class McpController {

    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String INSTRUCTIONS = "RUTERA is a fleet and transport management system for small "
            + "haulage companies: vehicles, drivers (tachograph hours under EU 561/2006, absences, pay), contractors, "
            + "orders, trips, CMR notes, invoices and debts, fuel cards and a trip calculator. The tools are the "
            + "operations of the RUTERA REST API v1 and act with the privileges of the token's user. Use the *_list "
            + "tools to find ids, dates are ISO (yyyy-MM-dd), times ISO instants (UTC), amounts in the currency of "
            + "the record. Create and update tools take the record under \"body\"; update (PUT) replaces the whole "
            + "record. Errors come back as {code, message, details} with per-field codes.";

    private final McpTools tools;
    private final ObjectMapper json;

    public McpController(McpTools tools, ObjectMapper json) {
        this.tools = tools;
        this.json = json;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(@RequestBody String payload, HttpServletRequest request) {
        JsonNode message;
        try {
            message = json.readTree(payload);
        } catch (JacksonException e) {
            return json(error(null, -32700, "Parse error"));
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (message.isArray()) {
            ArrayNode answers = json.createArrayNode();
            for (JsonNode m : message) {
                JsonNode answer = handle(m, authorization);
                if (answer != null) {
                    answers.add(answer);
                }
            }
            return answers.isEmpty() ? ResponseEntity.accepted().build() : json(answers);
        }
        JsonNode answer = handle(message, authorization);
        return answer == null ? ResponseEntity.accepted().build() : json(answer);
    }

    /** No server-initiated stream and no sessions: the client only POSTs. */
    @GetMapping("/mcp")
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> endSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /** The answer to one message, or null for notifications and client responses. */
    private JsonNode handle(JsonNode m, String authorization) {
        if (!m.isObject() || !m.path("jsonrpc").asString().equals("2.0")) {
            return error(m.isObject() ? m.get("id") : null, -32600, "Invalid request");
        }
        JsonNode id = m.get("id");
        String method = m.path("method").asString("");
        if (method.isEmpty()) {
            return null;
        }
        if (id == null || id.isNull()) {
            return null;
        }
        try {
            JsonNode params = m.path("params");
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, json.createObjectNode());
                case "tools/list" -> {
                    ObjectNode r = json.createObjectNode();
                    r.set("tools", tools.describe());
                    yield result(id, r);
                }
                case "tools/call" -> call(id, params, authorization);
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP {} failed: {}", method, e.toString());
            return error(id, -32603, "Internal error");
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asString("");
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst());
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "rutera");
        info.put("title", "RUTERA");
        info.put("version", "1");
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    /** Runs the tool; an HTTP error of the operation is a tool result with isError, not a JSON-RPC error. */
    private JsonNode call(JsonNode id, JsonNode params, String authorization) {
        String name = params.path("name").asString("");
        McpTools.Tool tool = tools.tool(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        JsonNode arguments = params.path("arguments").isObject() ? params.get("arguments") : json.createObjectNode();
        McpTools.Result res = tools.call(tool, arguments, authorization);
        ObjectNode r = json.createObjectNode();
        String text = res.body() == null || res.body().isBlank()
                ? "{\"status\":" + res.status() + "}" : res.body();
        ObjectNode content = r.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        try {
            JsonNode parsed = json.readTree(text);
            if (parsed.isObject()) {
                r.set("structuredContent", parsed);
            } else if (parsed.isArray()) {
                r.putObject("structuredContent").set("items", parsed);
            }
        } catch (JacksonException e) {
            // plain text answer: content only
        }
        r.put("isError", res.status() >= 400);
        return result(id, r);
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? json.nullNode() : id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }

    private ResponseEntity<String> json(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
    }
}
