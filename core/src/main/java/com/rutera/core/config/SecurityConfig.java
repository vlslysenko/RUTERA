package com.rutera.core.config;

import com.rutera.core.auth.AuditLogoutHandler;
import com.rutera.core.security.JsonSecurityErrors;
import com.rutera.core.security.RuteraSecurityProperties;
import com.rutera.core.security.SessionUserFilter;
import com.rutera.core.security.SessionUserService;
import com.rutera.core.security.SpaCsrfTokenRequestHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import java.util.Map;

/**
 * Security of the internal API for the React client (REQUIREMENTS.md section 7: session cookie + SameSite,
 * CSRF on). Compared with SMDP there is no form login: {@code POST /app/auth/login} takes JSON and the
 * controller stores the authentication in the session itself ({@code AuthService}).
 * <ul>
 *   <li>Session: cookie {@code RUTERA_SESSION} (HttpOnly, SameSite=Lax, see application.properties), created on
 *       login only; no request cache.</li>
 *   <li>CSRF: cookie {@code XSRF-TOKEN} (readable by JavaScript) mirrored in header {@code X-XSRF-TOKEN} on every
 *       state-changing request, login included.</li>
 *   <li>Access: {@code /app/**} needs a session except login and the i18n dictionaries; health is public;
 *       everything else (the static frontend served later) is open.</li>
 *   <li>Failures answer JSON ({@link JsonSecurityErrors}), never redirects.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(RuteraSecurityProperties.class)
public class SecurityConfig {

    public static final String LOGIN_PATH = "/app/auth/login";
    public static final String LOGOUT_PATH = "/app/auth/logout";
    public static final String SESSION_COOKIE = "RUTERA_SESSION";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JsonSecurityErrors errors,
                                            SessionUserService sessionUsers,
                                            SecurityContextRepository securityContextRepository,
                                            CsrfTokenRepository csrfTokenRepository,
                                            AuditLogoutHandler auditLogoutHandler) throws Exception {
        http
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .addLogoutHandler(auditLogoutHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies(SESSION_COOKIE)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, "/app/i18n/**").permitAll()
                        .requestMatchers("/app/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().permitAll())
                .addFilterAfter(new SessionUserFilter(sessionUsers), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Session storage of the authentication; the request-attribute part covers the login request itself. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    /**
     * Hashes are stored with the encoder id ({@code {bcrypt}$2a$12$...}), so the algorithm or cost can change
     * later without a migration: old hashes keep verifying, new ones use the current setting.
     */
    @Bean
    PasswordEncoder passwordEncoder(RuteraSecurityProperties properties) {
        String id = "bcrypt";
        return new DelegatingPasswordEncoder(id, Map.of(id, new BCryptPasswordEncoder(properties.bcryptStrength())));
    }
}
