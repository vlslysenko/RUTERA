package com.rutera.core.mcp;

import com.rutera.common.security.Role;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The AI role guides of {@code docs/ai-roles} (packaged as {@code classpath:/ai-roles/}, see core/pom.xml) as MCP
 * resources: {@code rutera://guide} is the guide of the caller's role, {@code rutera://guides/<name>} every guide by
 * name ({@code readme}, {@code owner}, {@code dispatcher}, {@code logistician}, {@code accountant}, {@code driver}).
 * Custom roles get the README, which tells them to follow the closest standard role and their own privileges.
 */
@Component
public class McpGuides {

    public static final String GUIDE_URI = "rutera://guide";
    static final String GUIDES_PREFIX = "rutera://guides/";
    static final String MIME = "text/markdown";

    /** Guide name -> {file, title}. */
    private static final Map<String, String[]> FILES = new LinkedHashMap<>();

    static {
        FILES.put("readme", new String[] {"README.md", "Guides for AI operators: connection and rules for all roles"});
        FILES.put("owner", new String[] {"owner.md", "Guide for the role Owner"});
        FILES.put("dispatcher", new String[] {"dispatcher.md", "Guide for the role Dispatcher"});
        FILES.put("logistician", new String[] {"logistician.md", "Guide for the role Logistician"});
        FILES.put("accountant", new String[] {"accountant.md", "Guide for the role Accountant"});
        FILES.put("driver", new String[] {"driver.md", "Guide for the role Driver"});
    }

    private final Map<String, String> texts = new LinkedHashMap<>();

    public McpGuides() {
        FILES.forEach((name, file) -> {
            ClassPathResource resource = new ClassPathResource("ai-roles/" + file[0]);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    texts.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    public record Guide(String uri, String name, String title, String text) {
    }

    /** The guide name of a role: its own guide for the standard roles, the README otherwise. */
    static String nameOf(Role role) {
        return switch (role) {
            case OWNER -> "owner";
            case DISPATCHER -> "dispatcher";
            case LOGISTICIAN -> "logistician";
            case ACCOUNTANT -> "accountant";
            case DRIVER -> "driver";
            default -> "readme";
        };
    }

    /** All resources for {@code resources/list}: the caller's guide first, then every guide by name. */
    public Map<String, Guide> all(Role role) {
        Map<String, Guide> result = new LinkedHashMap<>();
        own(role).ifPresent(g -> result.put(g.uri(), g));
        for (String name : texts.keySet()) {
            Guide g = byName(name).orElseThrow();
            result.put(g.uri(), g);
        }
        return result;
    }

    /** The resource of a URI for {@code resources/read}. */
    public Optional<Guide> read(String uri, Role role) {
        if (GUIDE_URI.equals(uri)) {
            return own(role);
        }
        return uri != null && uri.startsWith(GUIDES_PREFIX) ? byName(uri.substring(GUIDES_PREFIX.length()))
                : Optional.empty();
    }

    private Optional<Guide> own(Role role) {
        String name = nameOf(role);
        String text = texts.get(name);
        return text == null ? Optional.empty() : Optional.of(new Guide(GUIDE_URI, "guide",
                "Your guide (" + FILES.get(name)[1] + ")", text));
    }

    private Optional<Guide> byName(String name) {
        String text = texts.get(name);
        return text == null ? Optional.empty()
                : Optional.of(new Guide(GUIDES_PREFIX + name, name, FILES.get(name)[1], text));
    }
}
