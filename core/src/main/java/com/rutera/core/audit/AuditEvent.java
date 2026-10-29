package com.rutera.core.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry to be written to {@code audit_log}. Module and action are short English identifiers
 * ({@code auth}/{@code login}); the summary is a short English sentence for the journal screen.
 */
public record AuditEvent(
        String module,
        String action,
        String entity,
        Long entityId,
        String summary,
        Map<String, Object> details) {

    public static AuditEvent of(String module, String action, String summary) {
        return new AuditEvent(module, action, null, null, summary, null);
    }

    public AuditEvent entity(String entityName, Long id) {
        return new AuditEvent(module, action, entityName, id, summary, details);
    }

    public AuditEvent detail(String key, Object value) {
        Map<String, Object> copy = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        copy.put(key, value);
        return new AuditEvent(module, action, entity, entityId, summary, copy);
    }
}
