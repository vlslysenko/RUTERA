package com.rutera.core.auth;

import com.rutera.core.user.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in endpoints of the internal API (frontend: {@code src/api/auth.ts}).
 * <pre>
 * POST /app/auth/login  {login, password} -> 200 User | 401 {code: auth.login.*}
 * POST /app/auth/logout                   -> 204 (handled by Spring Security's logout filter)
 * GET  /app/auth/me                       -> 200 User | 401 {code: auth.unauthenticated}
 * </pre>
 */
@RestController
@RequestMapping("/app/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public UserDto login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        return authService.login(body, request, response);
    }

    @GetMapping("/me")
    public UserDto me(HttpServletRequest request, HttpServletResponse response) {
        return authService.me(request, response);
    }
}
