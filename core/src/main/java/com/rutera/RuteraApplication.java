package com.rutera;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RUTERA backend. Lives in the root package so that component scanning covers both modules
 * ({@code com.rutera.common.*} and {@code com.rutera.core.*}).
 * <p>
 * Like SMDP, an installation can override configuration without rebuilding: {@code conf/application.properties}
 * next to the jar is picked up automatically and {@code conf/logback.xml} replaces the built-in logging setup.
 */
@SpringBootApplication
public class RuteraApplication {

    public static void main(String[] args) {
        // All timestamps are UTC (docs/DB_SCHEMA.md); the firma time zone is for display only.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.ENGLISH);
        if (System.getProperty("spring.config.additional-location") == null
                && System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION") == null) {
            System.setProperty("spring.config.additional-location", "optional:file:./conf/");
        }
        if (System.getProperty("logging.config") == null && Files.isRegularFile(Path.of("conf", "logback.xml"))) {
            System.setProperty("logging.config", "conf/logback.xml");
        }
        SpringApplication.run(RuteraApplication.class, args);
    }
}
