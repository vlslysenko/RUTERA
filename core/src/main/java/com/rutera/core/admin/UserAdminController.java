package com.rutera.core.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User administration of the signed-in user's firma.
 * <pre>
 * GET    /app/admin/users                 -> AdminUser[]                     (admin.user/view)
 * GET    /app/admin/users/{id}            -> AdminUser | 404                 (admin.user/view)
 * POST   /app/admin/users                 {CreateUserRequest} -> 201 AdminUser (admin.user/manage)
 * PUT    /app/admin/users/{id}            {UpdateUserRequest} -> AdminUser   (admin.user/manage)
 * PUT    /app/admin/users/{id}/password   {newPassword} -> 204               (admin.user/manage)
 * POST   /app/admin/users/{id}/unlock     -> 204                             (admin.user/manage)
 * DELETE /app/admin/users/{id}            -> 204 (soft delete)               (admin.user/manage)
 * </pre>
 * 422 {@code error.validation} with per-field codes; 403 {@code admin.user.role_not_allowed} when the
 * hierarchy forbids it, 403 {@code admin.user.self_forbidden} for changes of one's own role, status or account.
 */
@RestController
@RequestMapping("/app/admin/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminUserDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public AdminUserDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserDto create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AdminUserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @RequestBody SetPasswordRequest request) {
        service.resetPassword(id, request);
    }

    @PostMapping("/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable Long id) {
        service.unlock(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
