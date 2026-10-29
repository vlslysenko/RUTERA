package com.rutera.core.user;

import com.rutera.common.domain.AppUser;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The user's own profile settings: interface language and start screen. */
@Service
public class UserSettingsService {

    public static final String MODULE = "profile";

    private final AppUserRepository users;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public UserSettingsService(AppUserRepository users, AuditService audit, ObjectMapper objectMapper) {
        this.users = users;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * Applies {@code PUT /app/me/settings} to the signed-in user and returns the updated row.
     * Invalid values answer 422 with one {@code validation.*} code per field.
     */
    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    @Transactional
    public AppUser updateOwn(UserSettingsPatch patch) {
        Long userId = CurrentUser.require().id();
        AppUser user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized(ErrorCodes.UNAUTHENTICATED));

        Map<String, String> errors = new LinkedHashMap<>();
        String language = null;
        if (patch.hasLanguage()) {
            language = readLanguage(patch.language(), errors);
        }
        String startScreen = null;
        boolean resetStartScreen = false;
        if (patch.hasStartScreen()) {
            JsonNode node = patch.startScreen();
            if (node.isNull()) {
                resetStartScreen = true;
            } else if (node.isString() && UserSettings.isStartScreen(node.asString())) {
                startScreen = node.asString();
            } else {
                errors.put("startScreen", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (language != null && !language.equals(user.getLang())) {
            user.setLang(language);
            changes.put("language", language);
        }
        if (resetStartScreen || startScreen != null) {
            UserSettings before = UserSettings.parse(objectMapper, user.getSettings());
            String after = resetStartScreen ? null : startScreen;
            if (!java.util.Objects.equals(before.startScreen(), after)) {
                user.setSettings(UserSettings.withStartScreen(objectMapper, user.getSettings(), after));
                changes.put("startScreen", after == null ? "default" : after);
            }
        }
        if (!changes.isEmpty()) {
            user = users.save(user);
            AuditEvent event = AuditEvent.of(MODULE, "settings_changed", "Changed own settings")
                    .entity("app_user", user.getId());
            for (Map.Entry<String, Object> change : changes.entrySet()) {
                event = event.detail(change.getKey(), change.getValue());
            }
            audit.record(event);
        }
        return user;
    }

    private static String readLanguage(JsonNode node, Map<String, String> errors) {
        if (node.isNull()) {
            errors.put("language", ErrorCodes.FIELD_REQUIRED);
            return null;
        }
        if (!node.isString()) {
            errors.put("language", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        String code = node.asString().trim().toLowerCase(Locale.ROOT);
        if (!SupportedLanguages.isSupported(code)) {
            errors.put("language", ErrorCodes.FIELD_INVALID_VALUE);
            return null;
        }
        return code;
    }
}
