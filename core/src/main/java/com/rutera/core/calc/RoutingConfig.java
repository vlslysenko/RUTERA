package com.rutera.core.calc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** The routing engine: Valhalla at {@code rutera.routing.url}, or none while the URL is empty. */
@Configuration
public class RoutingConfig {

    @Bean
    RouteProvider routeProvider(@Value("${rutera.routing.url:}") String url,
                                @Value("${rutera.routing.timeout:30s}") Duration timeout, ObjectMapper json) {
        return url == null || url.isBlank() ? new ManualRouteProvider()
                : new ValhallaRouteProvider(url.strip(), timeout, json);
    }
}
