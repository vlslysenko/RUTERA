package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 2.1 for MCP clients: discovery, dynamic client registration, the sign-in and consent page with PKCE, the
 * token endpoint (code exchange, refresh rotation, code reuse), revocation from the token list and by the client,
 * and the AI access add-on as the gate.
 */
class OAuthTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();
    private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Firma firma;
    private AppUser owner;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("OAuth Test " + n);
        firma.setShortCode("OAU" + n);
        firma.setCountry("PL");
        firma.setAiAccess(true);
        firma = firmas.save(firma);
        owner = createUser("owner-oauth" + n + "@test.local", "OAuthOwner-1", Role.OWNER, firma.getId(), true);
        return client();
    }

    /** A request of the browser on the authorization page: the client's session and CSRF cookie travel along. */
    private MvcResult browser(Client c, MockHttpServletRequestBuilder request) throws Exception {
        if (c.session != null) {
            request.session(c.session);
        }
        if (c.csrf != null) {
            request.cookie(new Cookie(CSRF_COOKIE, c.csrf));
        }
        MvcResult result = mvc.perform(request).andReturn();
        c.session = result.getRequest().getSession(false) instanceof MockHttpSession s && !s.isInvalid() ? s : null;
        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
            c.csrf = cookie.getValue();
        }
        return result;
    }

    private static Map<String, String> authorizeParams(String clientId, String verifier) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("response_type", "code");
        p.put("client_id", clientId);
        p.put("redirect_uri", REDIRECT);
        p.put("code_challenge", challenge(verifier));
        p.put("code_challenge_method", "S256");
        p.put("state", "st-42");
        p.put("scope", "rutera");
        p.put("resource", "http://localhost/mcp");
        return p;
    }

    private MvcResult showPage(Client c, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/oauth/authorize");
        params.forEach(get::queryParam);
        return browser(c, get);
    }

    /** Posts a form of the page: the request parameters, the CSRF token shown on the page and the extra fields. */
    private MvcResult submit(Client c, MvcResult page, Map<String, String> params, Map<String, String> fields)
            throws Exception {
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.post("/oauth/authorize")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(post::param);
        Matcher m = CSRF.matcher(page.getResponse().getContentAsString(StandardCharsets.UTF_8));
        if (m.find()) {
            post.param("_csrf", m.group(1));
        }
        fields.forEach(post::param);
        return browser(c, post);
    }

    /** Signs in on the page (unless the session exists), allows the client and returns the code. */
    private String authorize(Client c, String clientId, String verifier) throws Exception {
        Map<String, String> params = authorizeParams(clientId, verifier);
        MvcResult page = showPage(c, params);
        if (c.session == null) {
            MvcResult login = submit(c, page, params, Map.of("action", "login", "login", owner.getLogin(),
                    "password", "OAuthOwner-1"));
            assertThat(login.getResponse().getStatus()).isEqualTo(303);
            page = showPage(c, params);
        }
        assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("value=\"allow\"");
        MvcResult allowed = submit(c, page, params, Map.of("action", "allow"));
        assertThat(allowed.getResponse().getStatus()).isEqualTo(302);
        Map<String, List<String>> query = UriComponentsBuilder
                .fromUriString(allowed.getResponse().getHeader(HttpHeaders.LOCATION)).build().getQueryParams();
        assertThat(query.get("state")).containsExactly("st-42");
        assertThat(query.get("iss")).containsExactly("http%3A%2F%2Flocalhost");
        return query.get("code").getFirst();
    }

    private MvcResult token(Map<String, String> form) throws Exception {
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON);
        form.forEach(post::param);
        return mvc.perform(post).andReturn();
    }

    private MvcResult exchange(String clientId, String code, String verifier) throws Exception {
        return token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier));
    }

    private int apiStatus(String accessToken) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/vehicles").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn().getResponse().getStatus();
    }

    private String register(Map<String, Object> metadata) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(metadata))).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("client_id").asString();
    }

    private static String verifier() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void discoveryAndRegistration() throws Exception {
        MvcResult unauthorized = mvc.perform(MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andReturn();
        assertThat(unauthorized.getResponse().getStatus()).isEqualTo(401);
        assertThat(unauthorized.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(
                "Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");
        MvcResult badToken = mvc.perform(MockMvcRequestBuilders.get("/api/v1/vehicles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer rta_nothing")).andReturn();
        assertThat(badToken.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer "
                + "error=\"invalid_token\", resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/api/v1\"");

        JsonNode resource = body(mvc.perform(MockMvcRequestBuilders.get("/.well-known/oauth-protected-resource/mcp"))
                .andReturn());
        assertThat(resource.get("resource").asString()).isEqualTo("http://localhost/mcp");
        assertThat(resource.get("authorization_servers").get(0).asString()).isEqualTo("http://localhost");
        JsonNode server = body(mvc.perform(MockMvcRequestBuilders.get("/.well-known/oauth-authorization-server"))
                .andReturn());
        assertThat(server.get("issuer").asString()).isEqualTo("http://localhost");
        assertThat(server.get("token_endpoint").asString()).isEqualTo("http://localhost/oauth/token");
        assertThat(server.get("registration_endpoint").asString()).isEqualTo("http://localhost/oauth/register");
        assertThat(server.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");

        MvcResult evil = mvc.perform(MockMvcRequestBuilders.post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"http://evil.example/cb\"]}")).andReturn();
        assertThat(evil.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(evil).get("error").asString()).isEqualTo("invalid_redirect_uri");
        MvcResult implicit = mvc.perform(MockMvcRequestBuilders.post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirect_uris\":[\"" + REDIRECT + "\"],\"grant_types\":[\"implicit\"]}")).andReturn();
        assertThat(body(implicit).get("error").asString()).isEqualTo("invalid_client_metadata");
        assertThat(register(Map.of("redirect_uris", List.of("http://127.0.0.1:33418/callback"))))
                .as("loopback for native clients").hasSize(32);

        // A confidential client gets a secret once and must send it.
        MvcResult confidential = mvc.perform(MockMvcRequestBuilders.post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "client_name", "Secret app", "redirect_uris", List.of(REDIRECT),
                        "token_endpoint_auth_method", "client_secret_post")))).andReturn();
        JsonNode reg = body(confidential);
        assertThat(reg.get("client_secret").asString()).startsWith("rts_");
        MvcResult noSecret = token(Map.of("grant_type", "refresh_token", "refresh_token", "rtf_x",
                "client_id", reg.get("client_id").asString()));
        assertThat(noSecret.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(noSecret).get("error").asString()).isEqualTo("invalid_client");
        MvcResult withSecret = token(Map.of("grant_type", "refresh_token", "refresh_token", "rtf_x",
                "client_id", reg.get("client_id").asString(), "client_secret", reg.get("client_secret").asString()));
        assertThat(body(withSecret).get("error").asString()).as("authenticated, unknown token")
                .isEqualTo("invalid_grant");
    }

    @Test
    void connectUseRefreshAndRevoke() throws Exception {
        Client c = newFirmaOwner();
        String clientId = register(Map.of("client_name", "Claude", "redirect_uris", List.of(REDIRECT),
                "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code")));
        String verifier = verifier();
        Map<String, String> params = authorizeParams(clientId, verifier);

        // Faults of the client or the redirect URI stay on the page; other faults go back to the client.
        Map<String, String> unknown = new LinkedHashMap<>(params);
        unknown.put("client_id", "nope");
        MvcResult unknownPage = showPage(c, unknown);
        assertThat(unknownPage.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknownPage.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        Map<String, String> otherRedirect = new LinkedHashMap<>(params);
        otherRedirect.put("redirect_uri", "https://evil.example/cb");
        assertThat(showPage(c, otherRedirect).getResponse().getStatus()).isEqualTo(400);
        Map<String, String> noPkce = new LinkedHashMap<>(params);
        noPkce.remove("code_challenge");
        MvcResult noPkcePage = showPage(c, noPkce);
        assertThat(noPkcePage.getResponse().getStatus()).isEqualTo(302);
        assertThat(noPkcePage.getResponse().getHeader(HttpHeaders.LOCATION))
                .startsWith(REDIRECT + "?error=invalid_request").contains("state=st-42");

        // Sign-in on the page: a wrong password stays there, the form needs the CSRF token.
        MvcResult loginPage = showPage(c, params);
        assertThat(loginPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(loginPage.getResponse().getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(loginPage.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("name=\"password\"").contains("Claude")
                .as("Deny works without filling the sign-in fields").contains("value=\"deny\" formnovalidate");
        MvcResult wrong = submit(c, loginPage, params, Map.of("action", "login", "login", owner.getLogin(),
                "password", "wrong-password"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(200);
        assertThat(wrong.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("class=\"error\"");
        MockHttpServletRequestBuilder forged = MockMvcRequestBuilders.post("/oauth/authorize")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("action", "allow");
        params.forEach(forged::param);
        assertThat(browser(c, forged).getResponse().getStatus()).as("no CSRF token").isEqualTo(403);

        String code = authorize(c, clientId, verifier);
        MvcResult badVerifier = exchange(clientId, code, verifier());
        assertThat(body(badVerifier).get("error").asString()).isEqualTo("invalid_grant");
        MvcResult issued = exchange(clientId, code, verifier);
        assertThat(issued.getResponse().getStatus()).as(issued.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode pair = body(issued);
        String access = pair.get("access_token").asString();
        String refresh = pair.get("refresh_token").asString();
        assertThat(access).startsWith("rta_");
        assertThat(refresh).startsWith("rtf_");
        assertThat(pair.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(pair.get("expires_in").asLong()).isEqualTo(3600);

        assertThat(apiStatus(access)).isEqualTo(200);
        MvcResult init = mvc.perform(MockMvcRequestBuilders.post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"))
                .andReturn();
        assertThat(body(init).get("result").get("serverInfo").isObject()).isTrue();
        JsonNode list = body(c.get("/app/me/tokens"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("kind").asString()).isEqualTo("OAUTH");
        assertThat(list.get(0).get("name").asString()).isEqualTo("Claude");

        // Refresh rotates both tokens; the old ones stop working.
        JsonNode next = body(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", clientId)));
        String access2 = next.get("access_token").asString();
        assertThat(access2).isNotEqualTo(access);
        assertThat(apiStatus(access)).isEqualTo(401);
        assertThat(apiStatus(access2)).isEqualTo(200);
        assertThat(body(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id",
                clientId))).get("error").asString()).isEqualTo("invalid_grant");

        // The code a second time: refused, and the connection made from it ends.
        assertThat(body(exchange(clientId, code, verifier)).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(apiStatus(access2)).isEqualTo(401);

        // Second connection (the session exists now): revoked from the token list.
        String verifier2 = verifier();
        JsonNode second = body(exchange(clientId, authorize(c, clientId, verifier2), verifier2));
        assertThat(apiStatus(second.get("access_token").asString())).isEqualTo(200);
        JsonNode active = body(c.get("/app/me/tokens"));
        long connection = active.get(0).get("id").asLong();
        assertThat(active.get(0).get("active").asBoolean()).isTrue();
        assertThat(c.delete("/app/me/tokens/" + connection).getResponse().getStatus()).isEqualTo(204);
        assertThat(apiStatus(second.get("access_token").asString())).isEqualTo(401);
        assertThat(body(token(Map.of("grant_type", "refresh_token", "refresh_token",
                second.get("refresh_token").asString(), "client_id", clientId))).get("error").asString())
                .isEqualTo("invalid_grant");

        // Third connection: revoked by the client (RFC 7009).
        String verifier3 = verifier();
        JsonNode third = body(exchange(clientId, authorize(c, clientId, verifier3), verifier3));
        MockHttpServletRequestBuilder revoke = MockMvcRequestBuilders.post("/oauth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", third.get("refresh_token").asString()).param("client_id", clientId);
        assertThat(mvc.perform(revoke).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(apiStatus(third.get("access_token").asString())).isEqualTo(401);

        // Without the AI access add-on the page explains it and gives no code; refreshing stops as well.
        String verifier4 = verifier();
        JsonNode fourth = body(exchange(clientId, authorize(c, clientId, verifier4), verifier4));
        Firma reloaded = firmas.findById(firma.getId()).orElseThrow();
        reloaded.setAiAccess(false);
        firmas.save(reloaded);
        Map<String, String> again = authorizeParams(clientId, verifier());
        MvcResult blocked = showPage(c, again);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat(blocked.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("value=\"allow\"");
        MvcResult forcedAllow = submit(c, blocked, again, Map.of("action", "allow"));
        assertThat(forcedAllow.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(body(token(Map.of("grant_type", "refresh_token", "refresh_token",
                fourth.get("refresh_token").asString(), "client_id", clientId))).get("error").asString())
                .isEqualTo("invalid_grant");

        // Deny goes back to the client with access_denied.
        reloaded = firmas.findById(firma.getId()).orElseThrow();
        reloaded.setAiAccess(true);
        firmas.save(reloaded);
        MvcResult consent = showPage(c, again);
        MvcResult denied = submit(c, consent, again, Map.of("action", "deny"));
        assertThat(denied.getResponse().getHeader(HttpHeaders.LOCATION))
                .startsWith(REDIRECT + "?error=access_denied");
    }
}
