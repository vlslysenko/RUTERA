package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.repository.AuditLogRepository;
import com.rutera.common.security.Role;
import com.rutera.core.security.SessionUserFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-pilot security pass: failed sign-ins per client address, the absolute session age, the security headers of
 * the web application (and the stricter ones of the OAuth page), and the actuator showing health only.
 */
class SecurityHardeningTest extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLog;

    /** A sign-in from the given client address with a fresh CSRF token. */
    private MvcResult login(String address, String login, String password) throws Exception {
        MvcResult me = mvc.perform(MockMvcRequestBuilders.get("/app/auth/me").with(r -> {
            r.setRemoteAddr(address);
            return r;
        })).andReturn();
        String csrf = me.getResponse().getCookie(CSRF_COOKIE).getValue();
        return mvc.perform(MockMvcRequestBuilders.post("/app/auth/login").with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                }).contentType(MediaType.APPLICATION_JSON).cookie(new Cookie(CSRF_COOKIE, csrf))
                .header(CSRF_HEADER, csrf)
                .content(json.writeValueAsString(new LoginBody(login, password)))).andReturn();
    }

    @Test
    void failedSignInsFromOneAddressAreThrottled() throws Exception {
        AppUser user = createUser("throttle@test.local", "Throttle-Pass-1", Role.DISPATCHER, mainFirma().getId(),
                true);
        String attacker = "203.0.113.7";
        for (int i = 0; i < 20; i++) {
            MvcResult r = login(attacker, "nobody" + i + "@test.local", "wrong-password");
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult blocked = login(attacker, user.getLogin(), "Throttle-Pass-1");
        assertThat(blocked.getResponse().getStatus()).as("even with the right password").isEqualTo(429);
        assertThat(body(blocked).get("code").asString()).isEqualTo("auth.login.throttled");
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);
        assertThat(login("198.51.100.9", user.getLogin(), "Throttle-Pass-1").getResponse().getStatus())
                .as("another address signs in").isEqualTo(200);
        assertThat(auditLog.findAll().stream().filter(a -> "login_throttled".equals(a.getAction())
                && a.getDetails() != null && a.getDetails().contains(attacker))).hasSize(1);
    }

    @Test
    void aSessionEndsAfterItsAbsoluteAge() throws Exception {
        createUser("session-age@test.local", "Session-Age-1", Role.DISPATCHER, mainFirma().getId(), true);
        Client c = client();
        assertThat(c.login("session-age@test.local", "Session-Age-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(c.session.getAttribute(SessionUserFilter.SIGNED_IN_AT)).isInstanceOf(Instant.class);
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).isEqualTo(200);
        c.session.setAttribute(SessionUserFilter.SIGNED_IN_AT, Instant.now().minus(Duration.ofHours(13)));
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).as("older than 12 h").isEqualTo(401);
    }

    @Test
    void securityHeadersAndActuator() throws Exception {
        MvcResult app = mvc.perform(MockMvcRequestBuilders.get("/app/auth/me")).andReturn();
        String csp = app.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'self'", "frame-ancestors 'none'", "https://tile.openstreetmap.org",
                "worker-src 'self'").doesNotContain("form-action");
        assertThat(app.getResponse().getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(app.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(app.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(app.getResponse().getHeader("Permissions-Policy")).contains("microphone=()");

        MvcResult oauthPage = mvc.perform(MockMvcRequestBuilders.get("/oauth/authorize?client_id=none")).andReturn();
        assertThat(oauthPage.getResponse().getHeaders("Content-Security-Policy")).as("the page keeps its own")
                .containsExactly("default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");

        assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/info")).andReturn().getResponse().getStatus())
                .isNotEqualTo(200);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/env")).andReturn().getResponse().getStatus())
                .isNotEqualTo(200);
        assertThat(AuditLog.Channel.valueOf("UI")).isNotNull();
    }
}
