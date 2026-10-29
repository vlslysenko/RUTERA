package com.rutera.core.user;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code PUT /app/me/password}. Length rules are applied by {@link PasswordService}. */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
}
