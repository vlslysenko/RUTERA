package com.rutera.core.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First-start data for an empty database ({@code rutera.bootstrap.*}, fed from {@code RUTERA_BOOTSTRAP_*}
 * environment variables). Nothing here is used once a firma exists.
 *
 * @param enabled       set to false to skip the bootstrap entirely (cloud mode will create firmas itself)
 * @param firmaName     name of the first firma
 * @param firmaCountry  ISO 3166-1 alpha-2 country of the firma
 * @param language      interface language of the firma and the owner (ISO 639-1)
 * @param adminLogin    login of the owner account
 * @param adminPassword password of the owner account; empty means "generate one and print it in the log once"
 * @param adminName     display name of the owner, "First Last"
 */
@ConfigurationProperties(prefix = "rutera.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        String firmaName,
        String firmaCountry,
        String language,
        String adminLogin,
        String adminPassword,
        String adminName) {

    public BootstrapProperties {
        firmaName = blankTo(firmaName, "My company");
        firmaCountry = blankTo(firmaCountry, "PL").toUpperCase();
        language = blankTo(language, "en");
        adminLogin = blankTo(adminLogin, "admin").trim().toLowerCase();
        adminName = blankTo(adminName, "Administrator");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
