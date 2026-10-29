package com.rutera.core.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /app/admin/users}. {@code role} is one of OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT,
 * DRIVER, CUSTOM (with {@code customRoleId}); {@code language} defaults to the firma language; the initial
 * {@code password} is set by the administrator (invitations by e-mail come later).
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 254) String login,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @NotBlank String role,
        Long customRoleId,
        @Size(max = 40) String phone,
        @Size(max = 5) String language,
        String password) {
}
