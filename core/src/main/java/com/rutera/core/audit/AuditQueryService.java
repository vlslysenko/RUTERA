package com.rutera.core.audit;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.AuditLogRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.common.security.Role;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The audit journal of a firma ({@code GET /app/admin/audit}, {@code /api/v1/audit}, {@code admin.audit/view}):
 * newest first, filtered by period, user, module, channel and text. The channel shows where an action came from;
 * API and MCP are the actions "via AI". The operator (SUPERUSER) may pass {@code firmaId} or read all firmas;
 * anyone else reads their own firma only.
 */
@Service
public class AuditQueryService {

    static final int MAX_SIZE = 200;

    private final AuditLogRepository journal;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final ObjectMapper json;

    public AuditQueryService(AuditLogRepository journal, AppUserRepository users, FirmaRepository firmas,
                             ObjectMapper json) {
        this.journal = journal;
        this.users = users;
        this.firmas = firmas;
        this.json = json;
    }

    public record Entry(Long id, Instant at, Long firmaId, Long userId, String userLogin, String channel,
                        String module, String action, String entityType, Long entityId, String summary,
                        JsonNode details, String ip, String requestId) {
    }

    public record EntryPage(List<Entry> items, long total) {
    }

    @RequiresPrivilege(Privileges.ADMIN_AUDIT_VIEW)
    @Transactional(readOnly = true)
    public EntryPage list(Long firmaId, LocalDate from, LocalDate to, Long userId, String module, String channel,
                          String q, int page, int size) {
        AuthenticatedUser me = CurrentUser.require();
        Long scope;
        if (me.role() == Role.SUPERUSER) {
            scope = firmaId;
        } else {
            if (firmaId != null && !firmaId.equals(me.firmaId())) {
                throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
            }
            scope = me.firmaId();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        AuditLog.Channel ch = InputFields.parseEnum(AuditLog.Channel.class, "channel", channel, false, errors);
        if (page < 0) {
            errors.put("page", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.put("size", ErrorCodes.FIELD_INVALID_VALUE);
        }
        InputFields.dateOrder("to", from, to, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        ZoneId zone = zone(scope);
        Instant start = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        String mod = InputFields.blankToNull(module);
        String text = InputFields.blankToNull(q);
        Page<AuditLog> result = journal.search(scope, start, end, userId,
                mod == null ? null : mod.toLowerCase(Locale.ROOT), ch,
                text == null ? null : "%" + text.toLowerCase(Locale.ROOT) + "%", PageRequest.of(page, size));
        Map<Long, String> logins = new HashMap<>();
        for (AppUser u : users.findAllById(result.getContent().stream().map(AuditLog::getUserId)
                .filter(Objects::nonNull).distinct().toList())) {
            logins.put(u.getId(), u.getLogin());
        }
        return new EntryPage(result.getContent().stream().map(a -> toEntry(a, logins)).toList(),
                result.getTotalElements());
    }

    /** Days are cut in the firma's time zone; UTC across firmas. */
    private ZoneId zone(Long firmaId) {
        if (firmaId == null) {
            return ZoneOffset.UTC;
        }
        try {
            return firmas.findById(firmaId).map(Firma::getTimezone).map(ZoneId::of).orElse(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private Entry toEntry(AuditLog a, Map<Long, String> logins) {
        JsonNode details = null;
        if (a.getDetails() != null) {
            try {
                details = json.readTree(a.getDetails());
            } catch (JacksonException e) {
                details = json.valueToTree(a.getDetails());
            }
        }
        return new Entry(a.getId(), a.getAt(), a.getFirmaId(), a.getUserId(),
                a.getUserId() == null ? null : logins.get(a.getUserId()), a.getChannel().name(), a.getModule(),
                a.getAction(), a.getEntity(), a.getEntityId(), a.getSummary(), details, a.getIp(), a.getRequestId());
    }
}
