package com.rutera.core.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /app/admin/users/{id}}: the whole editable part of a user. Login and password are not
 * changed here ({@code /password} resets the password; the login is permanent, as in SMDP).
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @NotBlank String role,
        Long customRoleId,
        @Size(max = 40) String phone,
        @NotBlank @Size(max = 5) String language,
        @NotNull Boolean active) {
}
