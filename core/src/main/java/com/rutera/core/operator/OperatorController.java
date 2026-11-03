package com.rutera.core.operator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator console (role SUPERUSER only, see {@link OperatorService}).
 * <pre>
 * GET /app/operator/firmas?q=&amp;page=&amp;size= -> {items: FirmaItem[], total}
 * GET /app/operator/firmas/{id}                -> FirmaItem
 * PUT /app/operator/firmas/{id} {plan, vehicleLimit, aiAccess, active, expiresAt?} -> FirmaItem
 * </pre>
 */
@RestController
@RequestMapping("/app/operator/firmas")
public class OperatorController {

    private final OperatorService service;

    public OperatorController(OperatorService service) {
        this.service = service;
    }

    @GetMapping
    public OperatorService.FirmaPage list(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/{id}")
    public OperatorService.FirmaItem get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public OperatorService.FirmaItem update(@PathVariable Long id, @RequestBody OperatorService.FirmaInput input) {
        return service.update(id, input);
    }
}
