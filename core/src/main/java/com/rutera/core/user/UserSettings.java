package com.rutera.core.user;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Typed view of the {@code app_user.settings} JSON column. Only the keys the backend understands are modelled;
 * everything else in the column (table layouts etc.) is preserved untouched when a setting changes.
 *
 * @param startScreen screen key opened after sign-in, or {@code null} for the role default
 */
public record UserSettings(String startScreen) {

    public static final String KEY_START_SCREEN = "startScreen";

    /** Values accepted for {@code startScreen} ({@code ScreenKey} in the frontend). */
    public static final List<String> START_SCREENS = List.of(
            "overview", "map", "trips", "fleet", "drivers", "tacho", "documents", "finance", "fuel", "reports");

    public static final UserSettings EMPTY = new UserSettings(null);

    public static boolean isStartScreen(String value) {
        return value != null && START_SCREENS.contains(value);
    }

    /** Parses the column; unknown or malformed content yields {@link #EMPTY}. */
    public static UserSettings parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        JsonNode node = mapper.readTree(json);
        if (!node.isObject()) {
            return EMPTY;
        }
        JsonNode start = node.get(KEY_START_SCREEN);
        String startScreen = start != null && start.isString() ? start.asString() : null;
        return new UserSettings(isStartScreen(startScreen) ? startScreen : null);
    }

    /** Writes {@code startScreen} into the existing JSON (or a new object), keeping other keys. */
    public static String withStartScreen(ObjectMapper mapper, String existingJson, String startScreen) {
        ObjectNode node = null;
        if (existingJson != null && !existingJson.isBlank()) {
            JsonNode parsed = mapper.readTree(existingJson);
            if (parsed.isObject()) {
                node = (ObjectNode) parsed;
            }
        }
        if (node == null) {
            node = mapper.createObjectNode();
        }
        if (startScreen == null) {
            node.remove(KEY_START_SCREEN);
        } else {
            node.put(KEY_START_SCREEN, startScreen);
        }
        return node.isEmpty() ? null : mapper.writeValueAsString(node);
    }
}
