package com.rutera.core.api;

import com.rutera.common.domain.ApiCall;
import com.rutera.common.domain.ApiToken;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.ApiCallRepository;
import com.rutera.common.repository.ApiTokenRepository;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Personal tokens of the public API ({@code /app/me/tokens}, {@code /app/admin/tokens}). A token is "rtr_" plus 43
 * random base64url characters; only its SHA-256 hash and the first 12 characters are stored, the token itself is
 * shown once. Tokens can only be created while the firma has the AI access add-on.
 */
@Service
public class ApiTokenService {

    public static final String MODULE = "api";
    public static final String PREFIX = "rtr_";
    static final int MAX_ACTIVE_PER_USER = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;
    private final ApiCallRepository calls;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final AuditService audit;

    public ApiTokenService(ApiTokenRepository tokens, ApiCallRepository calls, AppUserRepository users,
                           FirmaRepository firmas, AuditService audit) {
        this.tokens = tokens;
        this.calls = calls;
        this.users = users;
        this.firmas = firmas;
        this.audit = audit;
    }

    public record Ref(Long id, String name) {
    }

    /** {@code token} is set only in the answer of the creation. */
    public record Token(Long id, String name, String prefix, Ref user, Instant createdAt, Instant expiresAt,
                        Instant lastUsedAt, Instant revokedAt, boolean active, String token) {
    }

    public record TokenInput(@Size(max = 100) String name, Instant expiresAt) {
    }

    public record Call(Instant at, String method, String path, int status, int durationMs, String ip) {
    }

    public record CallPage(List<Call> items, long total) {
    }

    // --- own tokens ----------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    @Transactional(readOnly = true)
    public List<Token> mine() {
        Long userId = CurrentUser.require().id();
        return tokens.findByUserIdOrderByIdDesc(userId).stream().map(t -> toDto(t, null, null)).toList();
    }

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    @Transactional
    public Token create(TokenInput input) {
        Long firmaId = InputFields.requireFirmaId();
        Long userId = CurrentUser.require().id();
        Firma firma = firmas.findById(firmaId).orElseThrow();
        if (!firma.isAiAccess()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.API_AI_ACCESS_REQUIRED);
        }
        Map<String, String> errors = new HashMap<>();
        String name = InputFields.blankToNull(input.name());
        if (name == null) {
            errors.put("name", ErrorCodes.FIELD_REQUIRED);
        } else if (tokens.countByUserIdAndRevokedAtIsNull(userId) >= MAX_ACTIVE_PER_USER) {
            errors.put("name", ErrorCodes.API_TOKEN_LIMIT);
        }
        if (input.expiresAt() != null && !input.expiresAt().isAfter(Instant.now())) {
            errors.put("expiresAt", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String raw = newToken();
        ApiToken t = new ApiToken();
        t.setFirmaId(firmaId);
        t.setUserId(userId);
        t.setName(name);
        t.setTokenPrefix(raw.substring(0, 12));
        t.setTokenHash(hash(raw));
        t.setExpiresAt(input.expiresAt());
        t = tokens.save(t);
        audit.record(AuditEvent.of(MODULE, "api_token_created", "Created API token " + t.getTokenPrefix() + "…")
                .entity("api_token", t.getId()));
        return toDto(t, null, raw);
    }

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    @Transactional
    public void revokeMine(Long id) {
        ApiToken t = load(id);
        if (!t.getUserId().equals(CurrentUser.require().id())) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND);
        }
        revoke(t);
    }

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    @Transactional(readOnly = true)
    public CallPage calls(Long id, int page, int size) {
        ApiToken t = load(id);
        if (!t.getUserId().equals(CurrentUser.require().id())
                && !CurrentUser.require().hasPrivilege(Privileges.ADMIN_API_TOKEN_MANAGE)) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND);
        }
        if (page < 0 || size < 1 || size > 200) {
            throw ApiException.validation(Map.of(page < 0 ? "page" : "size", ErrorCodes.FIELD_INVALID_VALUE));
        }
        Page<ApiCall> result = calls.findByTokenIdOrderByIdDesc(t.getId(), PageRequest.of(page, size));
        return new CallPage(result.getContent().stream().map(c -> new Call(c.getCreatedAt(), c.getMethod(),
                c.getPath(), c.getStatus(), c.getDurationMs(), c.getIp())).toList(), result.getTotalElements());
    }

    // --- all tokens of the firma ---------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.ADMIN_API_TOKEN_MANAGE)
    @Transactional(readOnly = true)
    public List<Token> all() {
        List<ApiToken> list = tokens.findAllByOrderByIdDesc();
        Map<Long, AppUser> userOf = new HashMap<>();
        for (AppUser u : users.findAllById(list.stream().map(ApiToken::getUserId).distinct().toList())) {
            userOf.put(u.getId(), u);
        }
        return list.stream().map(t -> toDto(t, userOf.get(t.getUserId()), null)).toList();
    }

    @RequiresPrivilege(Privileges.ADMIN_API_TOKEN_MANAGE)
    @Transactional
    public void revokeAny(Long id) {
        revoke(load(id));
    }

    private void revoke(ApiToken t) {
        if (t.getRevokedAt() == null) {
            t.setRevokedAt(Instant.now());
            tokens.save(t);
            audit.record(AuditEvent.of(MODULE, "api_token_revoked", "Revoked API token " + t.getTokenPrefix() + "…")
                    .entity("api_token", t.getId()));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private ApiToken load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return tokens.findById(id).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static Token toDto(ApiToken t, AppUser user, String raw) {
        Instant now = Instant.now();
        boolean active = t.getRevokedAt() == null && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(now));
        Ref ref = user == null ? null : new Ref(user.getId(),
                (Objects.requireNonNullElse(user.getFirstName(), "") + " "
                        + Objects.requireNonNullElse(user.getLastName(), "")).strip());
        return new Token(t.getId(), t.getName(), t.getTokenPrefix(), ref, t.getCreatedAt(), t.getExpiresAt(),
                t.getLastUsedAt(), t.getRevokedAt(), active, raw);
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex: the tokens are random enough that a slow hash adds nothing, and the lookup stays an index hit. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
