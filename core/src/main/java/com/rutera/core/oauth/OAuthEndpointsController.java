package com.rutera.core.oauth;

import com.rutera.common.domain.OAuthClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The machine-facing OAuth endpoints for MCP clients (stateless, no session, no CSRF; see SecurityConfig):
 * <ul>
 *   <li>{@code GET /.well-known/oauth-protected-resource[/mcp | /api/v1]}: protected resource metadata (RFC 9728);</li>
 *   <li>{@code GET /.well-known/oauth-authorization-server}: authorization server metadata (RFC 8414);</li>
 *   <li>{@code POST /oauth/register}: dynamic client registration (RFC 7591), at most 20 per address and hour;</li>
 *   <li>{@code POST /oauth/token}: authorization_code (PKCE) and refresh_token grants (RFC 6749, OAuth 2.1);</li>
 *   <li>{@code POST /oauth/revoke}: token revocation (RFC 7009).</li>
 * </ul>
 * The user-facing part, {@code /oauth/authorize}, is {@link OAuthAuthorizeController}.
 */
@RestController
public class OAuthEndpointsController {

    static final int REGISTRATIONS_PER_HOUR = 20;

    private record Window(long hour, int count) {
    }

    private final OAuthService oauth;
    private final OAuthUrls urls;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Window> registrations = new ConcurrentHashMap<>();

    public OAuthEndpointsController(OAuthService oauth, OAuthUrls urls, ObjectMapper json) {
        this.oauth = oauth;
        this.urls = urls;
        this.json = json;
    }

    // --- discovery -----------------------------------------------------------------------------------------

    @GetMapping(OAuthUrls.PROTECTED_RESOURCE)
    public Map<String, Object> resource(HttpServletRequest request) {
        return resourceMetadata(urls.base(request), "");
    }

    @GetMapping(OAuthUrls.PROTECTED_RESOURCE + "/mcp")
    public Map<String, Object> mcpResource(HttpServletRequest request) {
        return resourceMetadata(urls.base(request), "/mcp");
    }

    @GetMapping(OAuthUrls.PROTECTED_RESOURCE + "/api/v1")
    public Map<String, Object> apiResource(HttpServletRequest request) {
        return resourceMetadata(urls.base(request), "/api/v1");
    }

    private static Map<String, Object> resourceMetadata(String base, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", base + path);
        m.put("authorization_servers", List.of(base));
        m.put("scopes_supported", List.of(OAuthService.SCOPE));
        m.put("bearer_methods_supported", List.of("header"));
        m.put("resource_name", "RUTERA");
        return m;
    }

    @GetMapping(OAuthUrls.AUTHORIZATION_SERVER)
    public Map<String, Object> authorizationServer(HttpServletRequest request) {
        String base = urls.base(request);
        List<String> authMethods = List.of(OAuthClient.AUTH_NONE, OAuthClient.AUTH_SECRET_POST,
                OAuthClient.AUTH_SECRET_BASIC);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", base);
        m.put("authorization_endpoint", base + "/oauth/authorize");
        m.put("token_endpoint", base + "/oauth/token");
        m.put("registration_endpoint", base + "/oauth/register");
        m.put("revocation_endpoint", base + "/oauth/revoke");
        m.put("scopes_supported", List.of(OAuthService.SCOPE));
        m.put("response_types_supported", List.of("code"));
        m.put("response_modes_supported", List.of("query"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", authMethods);
        m.put("revocation_endpoint_auth_methods_supported", authMethods);
        m.put("authorization_response_iss_parameter_supported", true);
        return m;
    }

    // --- registration, token, revocation -------------------------------------------------------------------

    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody String body, HttpServletRequest request) {
        long hour = System.currentTimeMillis() / 3_600_000;
        String ip = String.valueOf(request.getRemoteAddr());
        Window w = registrations.compute(ip, (k, old) -> old == null || old.hour() != hour
                ? new Window(hour, 1) : new Window(hour, old.count() + 1));
        if (registrations.size() > 10_000) {
            registrations.entrySet().removeIf(e -> e.getValue().hour() < hour);
        }
        if (w.count() > REGISTRATIONS_PER_HOUR) {
            throw new OAuthError(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many registrations, try later");
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(body);
        } catch (JacksonException e) {
            throw new OAuthError(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "Malformed JSON");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(oauth.register(parsed));
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        String grantType = single(request, "grant_type");
        OAuthClient client = oauth.authenticate(single(request, "client_id"), single(request, "client_secret"),
                request.getHeader(HttpHeaders.AUTHORIZATION));
        OAuthService.Tokens tokens = switch (grantType == null ? "" : grantType) {
            case "authorization_code" -> oauth.exchange(client, single(request, "code"),
                    single(request, "redirect_uri"), single(request, "code_verifier"));
            case "refresh_token" -> oauth.refresh(client, single(request, "refresh_token"));
            case "" -> throw OAuthError.invalidRequest("grant_type is required");
            default -> throw new OAuthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "authorization_code and refresh_token only");
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access_token", tokens.accessToken());
        m.put("token_type", "Bearer");
        m.put("expires_in", tokens.expiresIn());
        m.put("refresh_token", tokens.refreshToken());
        m.put("scope", tokens.scope());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(m);
    }

    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(HttpServletRequest request) {
        OAuthClient client = oauth.authenticate(single(request, "client_id"), single(request, "client_secret"),
                request.getHeader(HttpHeaders.AUTHORIZATION));
        oauth.revoke(client, single(request, "token"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    /** A form parameter that may appear at most once (RFC 6749 section 3.1). */
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length > 1) {
            throw OAuthError.invalidRequest(name + " is repeated");
        }
        return values[0].isBlank() ? null : values[0];
    }

    @ExceptionHandler(OAuthError.class)
    public ResponseEntity<Map<String, Object>> error(OAuthError e, HttpServletRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getError());
        m.put("error_description", e.getMessage());
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(e.getStatus()).cacheControl(CacheControl.noStore());
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (e.getStatus() == HttpStatus.UNAUTHORIZED && authorization != null
                && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            answer.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"RUTERA\"");
        }
        return answer.contentType(MediaType.APPLICATION_JSON).body(m);
    }
}
