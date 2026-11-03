package com.rutera.core.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The audit journal (see {@link AuditQueryService}).
 * <pre>
 * GET /app/admin/audit?from=&amp;to=&amp;userId=&amp;module=&amp;channel=&amp;q=&amp;firmaId=&amp;page=&amp;size= -> {items: Entry[], total}
 * </pre>
 * Also under {@code /api/v1/audit} (and so the MCP tool {@code audit_list}): reading the journal is the one admin
 * function of the public API.
 */
@RestController
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping({"/app/admin/audit", "/api/v1/audit"})
    public AuditQueryService.EntryPage list(@RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) String channel,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Long firmaId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return service.list(firmaId, from, to, userId, module, channel, q, page, size);
    }
}
