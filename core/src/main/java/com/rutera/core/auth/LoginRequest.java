package com.rutera.core.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /app/auth/login}. */
public record LoginRequest(
        @NotBlank @Size(max = 254) String login,
        @NotBlank @Size(max = 200) String password) {
}
