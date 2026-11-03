package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public API: personal tokens (created once, listed without the secret, revoked), bearer calls under /api/v1 without
 * session or CSRF, the AI access add-on, privileges of the token's user, the call log, the rate limit (40 per minute
 * in the tests) and the OpenAPI document.
 */
class ApiTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    private Firma firma;

    @Autowired
    private AuditLogRepository auditLog;

    private AuditLog.Channel channelOf(String action) {
        return auditLog.findByFirmaIdOrderByAtDesc(firma.getId(), PageRequest.of(0, 50)).stream()
                .filter(a -> action.equals(a.getAction())).findFirst().orElseThrow().getChannel();
    }

    private Client newFirmaOwner(boolean aiAccess) throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Api Test " + n);
        firma.setShortCode("API" + n);
        firma.setCountry("PL");
        firma.setAiAccess(aiAccess);
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-api" + n + "@test.local", "ApiOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "ApiOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private MvcResult api(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private String token(Client c, String name) throws Exception {
        MvcResult res = c.post("/app/me/tokens", Map.of("name", name));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("token").asString();
    }

    @Test
    void tokensCallTheApiWithTheUsersPrivileges() throws Exception {
        Client c = newFirmaOwner(true);
        String token = token(c, "Integration");
        assertThat(token).startsWith("rtr_").hasSize(47);
        JsonNode list = body(c.get("/app/me/tokens"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("prefix").asString()).isEqualTo(token.substring(0, 12));
        assertThat(list.get(0).get("token").isNull()).as("never listed again").isTrue();
        long tokenId = list.get(0).get("id").asLong();

        assertThat(api(MockMvcRequestBuilders.get("/api/v1/vehicles"), null, null).getResponse().getStatus())
                .isEqualTo(401);
        MvcResult wrong = api(MockMvcRequestBuilders.get("/api/v1/vehicles"), "rtr_wrong", null);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("code").asString()).isEqualTo("auth.unauthenticated");

        MvcResult created = api(MockMvcRequestBuilders.post("/api/v1/vehicles"), token,
                Map.of("kind", "TRACTOR", "plate", "API 001"));
        assertThat(created.getResponse().getStatus()).as("bearer, no CSRF").isEqualTo(201);
        assertThat(channelOf("vehicle_created")).as("via the public API").isEqualTo(AuditLog.Channel.API);
        assertThat(channelOf("api_token_created")).as("in the web application").isEqualTo(AuditLog.Channel.UI);
        assertThat(created.getResponse().getCookie("RUTERA_SESSION")).as("stateless").isNull();
        JsonNode vehicles = body(api(MockMvcRequestBuilders.get("/api/v1/vehicles"), token, null));
        assertThat(vehicles.toString()).contains("API 001");
        assertThat(api(MockMvcRequestBuilders.get("/api/v1/debts"), token, null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(api(MockMvcRequestBuilders.get("/api/v1/tacho/violations"), token, null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(api(MockMvcRequestBuilders.get("/api/v1/me"), token, null).getResponse().getStatus())
                .as("no /me in the public API").isEqualTo(404);

        JsonNode calls = body(c.get("/app/me/tokens/" + tokenId + "/calls"));
        assertThat(calls.get("total").asLong()).isEqualTo(5);
        assertThat(calls.get("items").get(0).get("path").asString()).isEqualTo("/api/v1/me");
        assertThat(calls.get("items").get(0).get("status").asInt()).isEqualTo(404);
        assertThat(body(c.get("/app/me/tokens")).get(0).get("lastUsedAt").isNull()).isFalse();

        AppUser dispatcher = createUser("dispatcher-api" + firma.getId() + "@test.local", "ApiDisp-1",
                Role.DISPATCHER, firma.getId(), true);
        Client d = client();
        assertThat(d.login(dispatcher.getLogin(), "ApiDisp-1").getResponse().getStatus()).isEqualTo(200);
        String dispatcherToken = token(d, "Dispatcher script");
        MvcResult denied = api(MockMvcRequestBuilders.get("/api/v1/invoices"), dispatcherToken, null);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asString()).isEqualTo("auth.access_denied");
        assertThat(d.get("/app/admin/tokens").getResponse().getStatus()).isEqualTo(403);

        JsonNode all = body(c.get("/app/admin/tokens"));
        assertThat(all).hasSize(2);
        long dispatcherTokenId = all.get(0).get("id").asLong();
        assertThat(all.get(0).get("user").get("id").asLong()).isEqualTo(dispatcher.getId());
        assertThat(c.delete("/app/admin/tokens/" + dispatcherTokenId).getResponse().getStatus()).isEqualTo(204);
        assertThat(api(MockMvcRequestBuilders.get("/api/v1/vehicles"), dispatcherToken, null).getResponse()
                .getStatus()).as("revoked").isEqualTo(401);

        firma = firmas.findById(firma.getId()).orElseThrow();
        firma.setAiAccess(false);
        firma = firmas.save(firma);
        MvcResult noAddOn = api(MockMvcRequestBuilders.get("/api/v1/vehicles"), token, null);
        assertThat(noAddOn.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(noAddOn).get("code").asString()).isEqualTo("api.ai_access_required");
        MvcResult cannotCreate = c.post("/app/me/tokens", Map.of("name", "Second"));
        assertThat(cannotCreate.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(cannotCreate).get("code").asString()).isEqualTo("api.ai_access_required");

        firma = firmas.findById(firma.getId()).orElseThrow();
        firma.setAiAccess(true);
        firma = firmas.save(firma);
        assertThat(c.delete("/app/me/tokens/" + tokenId).getResponse().getStatus()).isEqualTo(204);
        assertThat(api(MockMvcRequestBuilders.get("/api/v1/vehicles"), token, null).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void theRateLimitAndTheOpenApiDocument() throws Exception {
        Client c = newFirmaOwner(true);
        String token = token(c, "Busy script");
        int last = 0;
        // 81 calls reach the limit even when a minute boundary resets the window on the way
        for (int i = 0; i < 81; i++) {
            MvcResult res = api(MockMvcRequestBuilders.get("/api/v1/contractors"), token, null);
            last = res.getResponse().getStatus();
            if (last == 429) {
                assertThat(res.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
                assertThat(body(res).get("code").asString()).isEqualTo("api.rate_limited");
                break;
            }
        }
        assertThat(last).as("over 40 calls in a minute").isEqualTo(429);

        MvcResult doc = mvc.perform(MockMvcRequestBuilders.get("/api/v1/openapi.json")).andReturn();
        assertThat(doc.getResponse().getStatus()).isEqualTo(200);
        JsonNode spec = json.readTree(doc.getResponse().getContentAsString());
        assertThat(spec.get("openapi").asString()).startsWith("3.");
        assertThat(spec.get("paths").has("/api/v1/vehicles")).isTrue();
        assertThat(spec.get("paths").has("/api/v1/cmr/{id}/pdf")).isTrue();
        assertThat(spec.get("paths").toString()).doesNotContain("\"/app/");
        assertThat(spec.get("components").get("securitySchemes").has("bearer")).isTrue();

        MvcResult noName = c.post("/app/me/tokens", Map.of("name", " "));
        assertThat(body(noName).get("details").get("name").asString()).isEqualTo("validation.required");
    }
}
