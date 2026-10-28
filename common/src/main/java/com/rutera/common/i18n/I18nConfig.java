package com.rutera.common.i18n;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One set of {@code messages_xx.properties} for all modules (SMDP had nine copies). Bundles come from the
 * classpath ({@code i18n/messages*}); an optional external directory lets an installation override texts
 * without a rebuild, as {@code etc/messages} did in SMDP.
 */
@Configuration
public class I18nConfig {

    /**
     * @param externalDir   directory with override bundles, e.g. {@code ./conf/i18n}; empty to disable
     * @param cacheSeconds  how often file bundles are checked for changes; -1 caches forever
     */
    @Bean
    public ExposedMessageSource messageSource(
            @Value("${rutera.i18n.external-dir:}") String externalDir,
            @Value("${rutera.i18n.cache-seconds:60}") int cacheSeconds) {
        ExposedMessageSource source = new ExposedMessageSource();
        List<String> basenames = new ArrayList<>();
        if (externalDir != null && !externalDir.isBlank()) {
            basenames.add("file:" + externalDir.replace('\\', '/') + "/messages");
        }
        basenames.add("classpath:i18n/messages");
        source.setBasenames(basenames.toArray(String[]::new));
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setDefaultLocale(Locale.ENGLISH);
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        source.setCacheSeconds(cacheSeconds);
        return source;
    }
}
