package com.rutera.core.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Personal API tokens of the signed-in user and, for administrators, of the whole firma.
 * <pre>
 * GET    /app/me/tokens                 -> Token[] (own)                                  (profile.self/manage)
 * POST   /app/me/tokens                 {name, expiresAt?} -> 201 Token with "token" once  (profile.self/manage)
 * DELETE /app/me/tokens/{id}            -> 204 (revoke)                                    (profile.self/manage)
 * GET    /app/me/tokens/{id}/calls?page&size -> {items: Call[], total} (own, or any with admin.api_token/manage)
 * GET    /app/admin/tokens              -> Token[] of the firma with user                  (admin.api_token/manage)
 * DELETE /app/admin/tokens/{id}         -> 204 (revoke)                                    (admin.api_token/manage)
 * </pre>
 */
@RestController
public class ApiTokenController {

    private final ApiTokenService service;

    public ApiTokenController(ApiTokenService service) {
        this.service = service;
    }

    @GetMapping("/app/me/tokens")
    public List<ApiTokenService.Token> mine() {
        return service.mine();
    }

    @PostMapping("/app/me/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token create(@Valid @RequestBody ApiTokenService.TokenInput input) {
        return service.create(input);
    }

    @DeleteMapping("/app/me/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        service.revokeMine(id);
    }

    @GetMapping("/app/me/tokens/{id}/calls")
    public ApiTokenService.CallPage calls(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return service.calls(id, page, size);
    }

    @GetMapping("/app/admin/tokens")
    public List<ApiTokenService.Token> all() {
        return service.all();
    }

    @DeleteMapping("/app/admin/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAny(@PathVariable Long id) {
        service.revokeAny(id);
    }
}
