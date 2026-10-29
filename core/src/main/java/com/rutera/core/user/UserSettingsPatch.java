package com.rutera.core.user;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code PUT /app/me/settings}: {@code {language?, startScreen?}}. A field that is absent stays as it
 * is; {@code "startScreen": null} resets the start screen to the role default. Values are validated by
 * {@link UserSettingsService}.
 */
public record UserSettingsPatch(JsonNode language, JsonNode startScreen) {

    public boolean hasLanguage() {
        return language != null;
    }

    public boolean hasStartScreen() {
        return startScreen != null;
    }
}
