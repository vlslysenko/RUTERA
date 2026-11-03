package com.rutera.core.audit;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.repository.AuditLogRepository;
import com.rutera.core.mcp.McpTools;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the audit journal (SMDP Dziennik). Firma, user and request id come from {@link RequestContext}, the
 * client address from the current HTTP request. The channel tells where the action came from: {@code UI} for the
 * web application ({@code /app}), {@code API} for the public API and the OAuth endpoints, {@code MCP} for tool
 * calls of the MCP server (its loopback calls of the API carry a secret {@code X-Rutera-Via}), {@code SYSTEM} for
 * scheduled jobs without a request. API and MCP are the actions "via AI".
 * <p>
 * {@link #record(AuditEvent)} joins the caller's transaction, so a rolled-back business operation leaves no
 * trace; {@link #recordSeparately} commits on its own and is meant for failed logins and other events that
 * must survive the surrounding failure.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int SUMMARY_MAX = 500;

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditEvent event) {
        record(event, RequestContext.firmaId().orElse(null), RequestContext.userId().orElse(null));
    }

    /** Same as {@link #record(AuditEvent)} but with an explicit firma and user (used before sign-in). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditEvent event, Long firmaId, Long userId) {
        repository.save(toEntity(event, firmaId, userId, channel()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSeparately(AuditEvent event, Long firmaId, Long userId) {
        repository.save(toEntity(event, firmaId, userId, channel()));
    }

    /** Channel of the current request (see the class comment). */
    static AuditLog.Channel channel() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return AuditLog.Channel.SYSTEM;
        }
        HttpServletRequest request = attrs.getRequest();
        String path = request.getRequestURI();
        if (path == null || !(path.startsWith("/api/") || path.startsWith("/oauth/") || path.equals("/mcp"))) {
            return AuditLog.Channel.UI;
        }
        return path.equals("/mcp") || McpTools.VIA_VALUE.equals(request.getHeader(McpTools.VIA_HEADER))
                ? AuditLog.Channel.MCP : AuditLog.Channel.API;
    }

    private AuditLog toEntity(AuditEvent event, Long firmaId, Long userId, AuditLog.Channel channel) {
        AuditLog row = new AuditLog();
        row.setFirmaId(firmaId);
        row.setUserId(userId);
        row.setAt(Instant.now());
        row.setChannel(channel);
        row.setModule(event.module());
        row.setAction(event.action());
        row.setEntity(event.entity());
        row.setEntityId(event.entityId());
        row.setSummary(truncate(event.summary()));
        row.setDetails(event.details() == null || event.details().isEmpty() ? null : toJson(event));
        row.setRequestId(RequestContext.requestId().orElseGet(() -> UUID.randomUUID().toString()));
        row.setIp(clientAddress());
        return row;
    }

    private String toJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.details());
        } catch (RuntimeException e) {
            log.warn("Audit details of {}/{} are not serialisable: {}", event.module(), event.action(), e.toString());
            return null;
        }
    }

    private static String truncate(String summary) {
        if (summary == null) {
            return "";
        }
        return summary.length() <= SUMMARY_MAX ? summary : summary.substring(0, SUMMARY_MAX);
    }

    private static String clientAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String addr = request.getRemoteAddr();
            return addr != null && addr.length() <= 45 ? addr : null;
        }
        return null;
    }
}
