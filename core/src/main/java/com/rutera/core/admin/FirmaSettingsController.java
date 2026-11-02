package com.rutera.core.admin;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The firma's settings (admin.firma/manage).
 * <pre>
 * GET /app/admin/firma -> Settings
 * PUT /app/admin/firma {name, vatId?, address?, email?, lang, timezone, baseCurrency, driversSeePay?} -> Settings
 * </pre>
 */
@RestController
public class FirmaSettingsController {

    private final FirmaSettingsService service;

    public FirmaSettingsController(FirmaSettingsService service) {
        this.service = service;
    }

    @GetMapping("/app/admin/firma")
    public FirmaSettingsService.Settings get() {
        return service.get();
    }

    @PutMapping("/app/admin/firma")
    public FirmaSettingsService.Settings update(@Valid @RequestBody FirmaSettingsService.SettingsInput input) {
        return service.update(input);
    }
}
