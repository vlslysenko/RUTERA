package com.rutera.core.config;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Serves the React client from the jar: the Maven profile {@code frontend} (and the Dockerfile) copy
 * {@code frontend/dist} to {@code classpath:/static/}. Boot's own static mappings are switched off
 * ({@code spring.web.resources.add-mappings=false}), so these two handlers are the only ones.
 * <ul>
 *   <li>{@code /assets/**}: Vite output with a content hash in the file name, cached for a year.</li>
 *   <li>Everything else ({@code index.html}, the service worker, the manifest, icons): revalidated on every load,
 *       so a new release is picked up at once.</li>
 *   <li>SPA fallback: a GET for a client-side route ({@code /fleet}, {@code /settings/users}) has no file of its
 *       own and gets {@code index.html}; the router in the browser takes over. A path whose last segment has an
 *       extension is a file and stays 404 when missing. {@code /app/**}, {@code /actuator/**} and {@code /error}
 *       never get the page, so an unknown API path keeps answering the JSON error.</li>
 * </ul>
 * Without a built client (plain {@code mvn package}) there is no {@code index.html} and these paths answer 404.
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    static final String LOCATION = "classpath:/static/";
    static final String INDEX = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(LOCATION + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        // No resource cache: the fallback answers any path, and caching per path would grow without bound.
        registry.addResourceHandler("/**")
                .addResourceLocations(LOCATION)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    /**
     * The PWA manifest is missing from Boot's MIME table and would go out as application/octet-stream. The
     * resource handler asks the servlet container first, so the mapping goes there (no effect under MockMvc).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> frontendMimeMappings() {
        return factory -> factory.addMimeMappings(new MimeMappings(Map.of("webmanifest", "application/manifest+json")));
    }

    /** Is {@code path} (relative, without the leading slash) a client-side route rather than a file or the API? */
    static boolean isClientRoute(String path) {
        String first = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        if (first.equals("app") || first.equals("actuator") || first.equals("error")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    /** Resolves files as usual and falls back to {@code index.html} for client-side routes. */
    static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (!resourcePath.isEmpty() && !resourcePath.endsWith("/")) {
                Resource resource = super.getResource(resourcePath, location);
                if (resource != null || !isClientRoute(resourcePath)) {
                    return resource;
                }
            } else if (!isClientRoute(resourcePath)) {
                return null;
            }
            return super.getResource(INDEX, location);
        }
    }
}
