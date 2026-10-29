package com.rutera.core.admin;

/** Body of {@code PUT /app/admin/users/{id}/password}; rules in {@code PasswordService}. */
public record SetPasswordRequest(String newPassword) {
}
