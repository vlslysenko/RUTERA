package com.rutera.core.web;

import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.security.JsonSecurityErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Safe replays of the driver cabinet's offline outbox: {@code POST /app/trips/{id}/files} and
 * {@code POST /app/drivers/{id}/absences} with an {@code Idempotency-Key} header (1-64 letters, digits, - or _). The
 * first request with a key runs as usual and its status and body are kept for 24 hours; the same key from the same
 * user then gets that result again ({@code Idempotency-Replayed: true}) without running anything. While the first
 * request still runs, another one gets 409 {@code request.in_progress}; the key on another path is 409
 * {@code error.conflict}. 5xx results are not kept, so a retry runs again. No or malformed header: as without this
 * filter. Runs after the authorization filter, so CSRF and the session are checked before.
 * Not a Spring bean: {@code SecurityConfig} adds it to the session chain.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    static final Duration KEEP = Duration.ofHours(24);
    static final int MAX_BODY = 1 << 20;
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern PATHS = Pattern.compile("/app/trips/\\d+/files|/app/drivers/\\d+/absences");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JdbcTemplate jdbc;
    private final JsonSecurityErrors errors;

    public IdempotencyFilter(JdbcTemplate jdbc, JsonSecurityErrors errors) {
        this.jdbc = jdbc;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String key = request.getHeader(HEADER);
        return !"POST".equals(request.getMethod()) || key == null || !KEY.matcher(key).matches()
                || !PATHS.matcher(request.getRequestURI()).matches() || CurrentUser.get().isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthenticatedUser user = CurrentUser.get().orElseThrow();
        String key = request.getHeader(HEADER);
        String path = request.getRequestURI();
        Instant now = Instant.now();
        try {
            jdbc.update("DELETE FROM idempotency_record WHERE created_at < ?", Timestamp.from(now.minus(KEEP)));
            jdbc.update("INSERT INTO idempotency_record (user_id, idem_key, method, path, status, created_at) "
                    + "VALUES (?, ?, 'POST', ?, 0, ?)", user.id(), key, path, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            replay(request, response, user.id(), key, path);
            return;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean kept = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            byte[] body = wrapper.getContentAsByteArray();
            if (status < 500 && body.length <= MAX_BODY) {
                jdbc.update("UPDATE idempotency_record SET status = ?, content_type = ?, body = ? "
                        + "WHERE user_id = ? AND idem_key = ?", status, wrapper.getContentType(),
                        new String(body, StandardCharsets.UTF_8), user.id(), key);
                kept = true;
            }
        } finally {
            if (!kept) {
                try {
                    jdbc.update("DELETE FROM idempotency_record WHERE user_id = ? AND idem_key = ?", user.id(), key);
                } catch (RuntimeException e) {
                    log.warn("Idempotency key of user {} not released: {}", user.id(), e.toString());
                }
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void replay(HttpServletRequest request, HttpServletResponse response, Long userId, String key,
                        String path) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT path, status, content_type, body "
                + "FROM idempotency_record WHERE user_id = ? AND idem_key = ?", userId, key);
        if (rows.isEmpty()) {
            errors.write(request, response, HttpStatus.CONFLICT, ErrorCodes.REQUEST_IN_PROGRESS);
            return;
        }
        Map<String, Object> row = rows.getFirst();
        int status = ((Number) row.get("status")).intValue();
        if (!path.equals(row.get("path"))) {
            errors.write(request, response, HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
            return;
        }
        if (status == 0) {
            errors.write(request, response, HttpStatus.CONFLICT, ErrorCodes.REQUEST_IN_PROGRESS);
            return;
        }
        response.setStatus(status);
        response.setHeader("Idempotency-Replayed", "true");
        if (row.get("content_type") != null) {
            response.setContentType((String) row.get("content_type"));
        }
        Object body = row.get("body");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
        response.flushBuffer();
    }
}
