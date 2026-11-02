package com.rutera.core.oauth;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.ApiToken;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.OAuthClient;
import com.rutera.common.domain.OAuthCode;
import com.rutera.common.repository.ApiTokenRepository;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.OAuthClientRepository;
import com.rutera.common.repository.OAuthCodeRepository;
import com.rutera.common.security.Role;
import com.rutera.core.api.ApiTokenService;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.SessionUserService;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * OAuth 2.1 authorization server for MCP clients (the claude.ai connector, Claude Code): dynamic client registration,
 * authorization codes with PKCE (S256 only), access and refresh tokens. A connection is an {@link ApiToken} of kind
 * OAUTH named after the client, so it passes the same AI access gate, rate limit and call log as a personal token and
 * shows up in the token lists, where revoking it disconnects the client. The access token ("rta_...", one hour) and
 * the refresh token ("rtf_...", 30 days after the last refresh) are stored as SHA-256 hashes and rotate on every
 * refresh. One scope, {@link #SCOPE}: the user's own privileges.
 */
@Service
public class OAuthService {

    public static final String SCOPE = "rutera";
    public static final String ACCESS_PREFIX = "rta_";
    static final String REFRESH_PREFIX = "rtf_";
    static final Duration CODE_TTL = Duration.ofSeconds(60);
    static final Duration ACCESS_TTL = Duration.ofHours(1);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    static final int MAX_REDIRECT_URIS = 10;
    private static final Set<String> GRANT_TYPES = Set.of("authorization_code", "refresh_token");
    private static final Set<String> AUTH_METHODS = Set.of(OAuthClient.AUTH_NONE, OAuthClient.AUTH_SECRET_POST,
            OAuthClient.AUTH_SECRET_BASIC);
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthClientRepository clients;
    private final OAuthCodeRepository codes;
    private final ApiTokenRepository tokens;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final SessionUserService sessionUsers;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public OAuthService(OAuthClientRepository clients, OAuthCodeRepository codes, ApiTokenRepository tokens,
                        AppUserRepository users, FirmaRepository firmas, SessionUserService sessionUsers,
                        AuditService audit, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.clients = clients;
        this.codes = codes;
        this.tokens = tokens;
        this.users = users;
        this.firmas = firmas;
        this.sessionUsers = sessionUsers;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Answer of the token endpoint. */
    public record Tokens(String accessToken, long expiresIn, String refreshToken, String scope) {
    }

    // --- registration (RFC 7591) ---------------------------------------------------------------------------

    /** Registers a client from its metadata; the answer is the registration response. */
    public Map<String, Object> register(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw metadata("A JSON object is expected");
        }
        JsonNode uris = body.get("redirect_uris");
        if (uris == null || !uris.isArray() || uris.isEmpty() || uris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthError(HttpStatus.BAD_REQUEST, "invalid_redirect_uri",
                    "redirect_uris: 1 to " + MAX_REDIRECT_URIS + " URIs");
        }
        List<String> redirects = new ArrayList<>();
        for (JsonNode uri : uris) {
            if (!uri.isString() || !validRedirect(uri.asString())) {
                throw new OAuthError(HttpStatus.BAD_REQUEST, "invalid_redirect_uri",
                        "Redirect URIs must be absolute https URIs or http on localhost, without a fragment");
            }
            if (!redirects.contains(uri.asString())) {
                redirects.add(uri.asString());
            }
        }
        String method = text(body, "token_endpoint_auth_method");
        method = method == null ? OAuthClient.AUTH_NONE : method;
        if (!AUTH_METHODS.contains(method)) {
            throw metadata("token_endpoint_auth_method: none, client_secret_post or client_secret_basic");
        }
        JsonNode grants = body.get("grant_types");
        if (grants != null && (!grants.isArray() || !allIn(grants, GRANT_TYPES))) {
            throw metadata("grant_types: authorization_code and refresh_token only");
        }
        JsonNode responses = body.get("response_types");
        if (responses != null && (!responses.isArray() || !allIn(responses, Set.of("code")))) {
            throw metadata("response_types: code only");
        }
        String name = text(body, "client_name");
        if (name == null) {
            name = URI.create(redirects.getFirst()).getHost();
        }
        name = name.length() > 100 ? name.substring(0, 100) : name;

        OAuthClient client = new OAuthClient();
        client.setClientId(random(24));
        client.setClientName(name);
        client.setRedirectUris(json.writeValueAsString(redirects));
        client.setAuthMethod(method);
        String secret = OAuthClient.AUTH_NONE.equals(method) ? null : "rts_" + random(32);
        client.setClientSecretHash(secret == null ? null : ApiTokenService.hash(secret));
        Instant now = Instant.now();
        OAuthClient saved = root(() -> tx.execute(s -> {
            clients.deleteUnusedBefore(now.minus(Duration.ofDays(7)));
            return clients.save(client);
        }));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("client_id", saved.getClientId());
        answer.put("client_id_issued_at", now.getEpochSecond());
        if (secret != null) {
            answer.put("client_secret", secret);
            answer.put("client_secret_expires_at", 0);
        }
        answer.put("client_name", saved.getClientName());
        answer.put("redirect_uris", redirects);
        answer.put("grant_types", List.of("authorization_code", "refresh_token"));
        answer.put("response_types", List.of("code"));
        answer.put("token_endpoint_auth_method", method);
        answer.put("scope", SCOPE);
        return answer;
    }

    /** https with a host, or http on the loopback interface (native clients such as Claude Code); no fragment. */
    static boolean validRedirect(String uri) {
        if (uri.length() > 500) {
            return false;
        }
        try {
            URI u = new URI(uri);
            if (!u.isAbsolute() || u.getRawFragment() != null || u.getHost() == null) {
                return false;
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(scheme) || ("http".equals(scheme)
                    && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // --- clients -------------------------------------------------------------------------------------------

    public Optional<OAuthClient> client(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 64) {
            return Optional.empty();
        }
        return root(() -> tx.execute(s -> clients.findByClientId(clientId)));
    }

    public List<String> redirectUris(OAuthClient client) {
        List<String> list = new ArrayList<>();
        for (JsonNode n : json.readTree(client.getRedirectUris())) {
            list.add(n.asString());
        }
        return list;
    }

    /**
     * The client of a token or revocation request: client_id (and client_secret) from the form, or HTTP Basic
     * (RFC 6749 section 2.3.1). Public clients only identify themselves; the others must send their secret.
     */
    public OAuthClient authenticate(String clientIdParam, String secretParam, String authorization) {
        String clientId = clientIdParam;
        String secret = secretParam;
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw OAuthError.invalidClient("Malformed Basic credentials");
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw OAuthError.invalidClient("Malformed Basic credentials");
            }
            clientId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        }
        OAuthClient client = client(clientId).orElseThrow(() -> OAuthError.invalidClient("Unknown client"));
        if (client.getClientSecretHash() != null && (secret == null || !MessageDigest.isEqual(
                ApiTokenService.hash(secret).getBytes(StandardCharsets.US_ASCII),
                client.getClientSecretHash().getBytes(StandardCharsets.US_ASCII)))) {
            throw OAuthError.invalidClient("Client authentication failed");
        }
        return client;
    }

    // --- authorization code --------------------------------------------------------------------------------

    static boolean validChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    /** A new code for the signed-in user (session request, firma scope set), after the user allowed the client. */
    public String issueCode(AuthenticatedUser user, OAuthClient client, String redirectUri, String challenge,
                            String resource) {
        String raw = random(32);
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> {
            codes.deleteExpiredBefore(now.minus(Duration.ofDays(1)));
            OAuthCode code = new OAuthCode();
            code.setFirmaId(user.firmaId());
            code.setUserId(user.id());
            code.setClientId(client.getId());
            code.setCodeHash(ApiTokenService.hash(raw));
            code.setRedirectUri(redirectUri);
            code.setCodeChallenge(challenge);
            code.setScope(SCOPE);
            code.setResource(resource == null || resource.length() > 255 ? null : resource);
            code.setExpiresAt(now.plus(CODE_TTL));
            codes.save(code);
        });
        return raw;
    }

    /** {@code grant_type=authorization_code}: the code becomes a connection with its first token pair. */
    public Tokens exchange(OAuthClient client, String rawCode, String redirectUri, String verifier) {
        if (blank(rawCode) || blank(redirectUri) || blank(verifier)) {
            throw OAuthError.invalidRequest("code, redirect_uri and code_verifier are required");
        }
        OAuthCode found = root(() -> tx.execute(s -> codes.findByCodeHash(ApiTokenService.hash(rawCode))))
                .orElseThrow(() -> OAuthError.invalidGrant("Unknown authorization code"));
        Instant now = Instant.now();
        Object result = firma(found.getFirmaId(), found.getUserId(), () -> tx.execute(s -> {
            OAuthCode code = codes.findById(found.getId()).orElseThrow();
            if (!code.getClientId().equals(client.getId())) {
                return OAuthError.invalidGrant("The code belongs to another client");
            }
            if (code.getUsedAt() != null) {
                // A second use means the code leaked: the connection made from it ends as well (RFC 6749 4.1.2).
                if (code.getApiTokenId() != null) {
                    tokens.findById(code.getApiTokenId()).ifPresent(t -> revoke(t, now, "oauth_code_reused"));
                }
                return OAuthError.invalidGrant("The authorization code was already used");
            }
            if (!code.getExpiresAt().isAfter(now)) {
                return OAuthError.invalidGrant("The authorization code has expired");
            }
            if (!code.getRedirectUri().equals(redirectUri)) {
                return OAuthError.invalidGrant("redirect_uri differs from the authorization request");
            }
            if (!VERIFIER.matcher(verifier).matches() || !s256(verifier).equals(code.getCodeChallenge())) {
                return OAuthError.invalidGrant("code_verifier does not match the code_challenge");
            }
            code.setUsedAt(now);
            String refusal = refusal(code.getUserId(), code.getFirmaId(), now);
            if (refusal != null) {
                codes.save(code);
                return OAuthError.invalidGrant(refusal);
            }
            for (ApiToken old : tokens.findByUserIdAndOauthClientIdAndRevokedAtIsNull(code.getUserId(),
                    client.getId())) {
                revoke(old, now, "oauth_reconnected");
            }
            ApiToken t = new ApiToken();
            t.setFirmaId(code.getFirmaId());
            t.setUserId(code.getUserId());
            t.setKind(ApiToken.Kind.OAUTH);
            t.setOauthClientId(client.getId());
            t.setName(client.getClientName());
            t.setTokenPrefix("OAuth");
            Tokens issued = rotate(t, now);
            t = tokens.save(t);
            code.setApiTokenId(t.getId());
            codes.save(code);
            OAuthClient c = clients.findById(client.getId()).orElseThrow();
            c.setLastUsedAt(now);
            clients.save(c);
            audit.record(AuditEvent.of(ApiTokenService.MODULE, "oauth_connected",
                    "Connected OAuth client " + client.getClientName()).entity("api_token", t.getId()),
                    code.getFirmaId(), code.getUserId());
            return issued;
        }));
        if (result instanceof OAuthError error) {
            throw error;
        }
        return (Tokens) result;
    }

    // --- refresh and revocation ----------------------------------------------------------------------------

    /** {@code grant_type=refresh_token}: a new token pair; the old refresh token stops working. */
    public Tokens refresh(OAuthClient client, String rawRefresh) {
        if (blank(rawRefresh)) {
            throw OAuthError.invalidRequest("refresh_token is required");
        }
        ApiToken found = rawRefresh.startsWith(REFRESH_PREFIX) && rawRefresh.length() <= 100
                ? root(() -> tx.execute(s -> tokens.findByRefreshHash(ApiTokenService.hash(rawRefresh)))).orElse(null)
                : null;
        if (found == null) {
            throw OAuthError.invalidGrant("Unknown refresh token");
        }
        Instant now = Instant.now();
        Object result;
        try {
            result = firma(found.getFirmaId(), found.getUserId(), () -> tx.execute(s -> {
                ApiToken t = tokens.findById(found.getId()).orElseThrow();
                if (t.getKind() != ApiToken.Kind.OAUTH || !client.getId().equals(t.getOauthClientId())) {
                    return OAuthError.invalidGrant("The refresh token belongs to another client");
                }
                if (t.getRevokedAt() != null || t.getExpiresAt() == null || !t.getExpiresAt().isAfter(now)
                        || !ApiTokenService.hash(rawRefresh).equals(t.getRefreshHash())) {
                    return OAuthError.invalidGrant("The refresh token is no longer valid");
                }
                String refusal = refusal(t.getUserId(), t.getFirmaId(), now);
                if (refusal != null) {
                    return OAuthError.invalidGrant(refusal);
                }
                Tokens issued = rotate(t, now);
                tokens.saveAndFlush(t);
                return issued;
            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw OAuthError.invalidGrant("The refresh token was used concurrently");
        }
        if (result instanceof OAuthError error) {
            throw error;
        }
        return (Tokens) result;
    }

    /** RFC 7009: ends the connection of an access or refresh token of this client; unknown tokens are ignored. */
    public void revoke(OAuthClient client, String raw) {
        if (blank(raw) || raw.length() > 100) {
            return;
        }
        String hash = ApiTokenService.hash(raw);
        ApiToken found = root(() -> tx.execute(s -> tokens.findByRefreshHash(hash)
                .or(() -> tokens.findByTokenHash(hash)))).orElse(null);
        if (found == null || found.getKind() != ApiToken.Kind.OAUTH
                || !client.getId().equals(found.getOauthClientId())) {
            return;
        }
        firma(found.getFirmaId(), found.getUserId(), () -> tx.execute(s -> {
            tokens.findById(found.getId()).ifPresent(t -> revoke(t, Instant.now(), "oauth_revoked"));
            return null;
        }));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** Why the user's connection may not get tokens, or null: the same checks as a request with a token. */
    private String refusal(Long userId, Long firmaId, Instant now) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || user.getRole() == Role.SUPERUSER || !firmaId.equals(user.getFirmaId())
                || sessionUsers.rejectionOf(user, now).isPresent()) {
            return "The user can no longer sign in";
        }
        Firma firma = firmas.findById(firmaId).orElse(null);
        if (firma == null || !firma.isAiAccess()) {
            return "The company has no AI access add-on";
        }
        return null;
    }

    /** New access and refresh tokens on {@code t} (not saved). */
    private static Tokens rotate(ApiToken t, Instant now) {
        String access = ACCESS_PREFIX + random(32);
        String refresh = REFRESH_PREFIX + random(32);
        t.setTokenHash(ApiTokenService.hash(access));
        t.setAccessExpiresAt(now.plus(ACCESS_TTL));
        t.setRefreshHash(ApiTokenService.hash(refresh));
        t.setExpiresAt(now.plus(REFRESH_TTL));
        return new Tokens(access, ACCESS_TTL.toSeconds(), refresh, SCOPE);
    }

    private void revoke(ApiToken t, Instant now, String action) {
        if (t.getRevokedAt() == null) {
            t.setRevokedAt(now);
            tokens.save(t);
            audit.record(AuditEvent.of(ApiTokenService.MODULE, action, "Disconnected OAuth client " + t.getName())
                    .entity("api_token", t.getId()), t.getFirmaId(), t.getUserId());
        }
    }

    /** BASE64URL(SHA-256(verifier)) without padding (RFC 7636 section 4.6). */
    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isString()) {
            throw metadata(field + " must be a string");
        }
        String s = n.asString().strip();
        return s.isEmpty() ? null : s;
    }

    private static boolean allIn(JsonNode array, Set<String> allowed) {
        for (JsonNode n : array) {
            if (!n.isString() || !allowed.contains(n.asString())) {
                return false;
            }
        }
        return true;
    }

    private static OAuthError metadata(String description) {
        return new OAuthError(HttpStatus.BAD_REQUEST, "invalid_client_metadata", description);
    }

    private static <T> T root(Callable<T> work) {
        return call(() -> RequestContext.runAsRoot(null, work));
    }

    private static <T> T firma(Long firmaId, Long userId, Callable<T> work) {
        return call(() -> RequestContext.runAsFirma(firmaId, userId, work));
    }

    private static <T> T call(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
