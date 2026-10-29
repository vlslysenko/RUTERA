package com.rutera.core.user;

import com.rutera.common.domain.AppUser;
import com.rutera.core.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own profile.
 * <pre>
 * PUT /app/me/settings {language?, startScreen?} -> 200 User | 422 {code: error.validation, details}
 * </pre>
 */
@RestController
@RequestMapping("/app/me")
public class MeController {

    private final UserSettingsService settings;
    private final AuthService authService;

    public MeController(UserSettingsService settings, AuthService authService) {
        this.settings = settings;
        this.authService = authService;
    }

    @PutMapping("/settings")
    public UserDto updateSettings(@RequestBody UserSettingsPatch patch, HttpServletRequest request,
                                  HttpServletResponse response) {
        AppUser user = settings.updateOwn(patch);
        authService.refreshPrincipal(user, request, response);
        return authService.toDto(user);
    }
}
