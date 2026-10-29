package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Role;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One MySQL 8.4 container (Testcontainers) and one Spring context for all web tests. The schema comes from
 * the Flyway migrations, the first firma and owner from the bootstrap (see application-test.properties).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rutera")
            .withUsername("rutera")
            .withPassword("rutera")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("characterEncoding", "UTF-8")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    protected static final String OWNER_LOGIN = "owner@test.local";
    protected static final String OWNER_PASSWORD = "Owner-Pass-1234";
    protected static final String CSRF_COOKIE = "XSRF-TOKEN";
    protected static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected AppUserRepository users;
    @Autowired
    protected FirmaRepository firmas;
    @Autowired
    protected PasswordEncoder passwordEncoder;

    /** A browser-like client: keeps the session and the CSRF cookie between calls. */
    protected final class Client {
        MockHttpSession session;
        String csrf;

        public MvcResult get(String path) throws Exception {
            return perform(MockMvcRequestBuilders.get(path));
        }

        public MvcResult post(String path, Object body) throws Exception {
            return perform(withBody(MockMvcRequestBuilders.post(path), body));
        }

        public MvcResult put(String path, Object body) throws Exception {
            return perform(withBody(MockMvcRequestBuilders.put(path), body));
        }

        /** Same as {@link #post} but without the CSRF header (the cookie still travels). */
        public MvcResult postWithoutCsrfHeader(String path, Object body) throws Exception {
            String saved = csrf;
            csrf = null;
            try {
                return perform(withBody(MockMvcRequestBuilders.post(path), body));
            } finally {
                csrf = saved;
            }
        }

        private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
            if (body != null) {
                String content = body instanceof String s ? s : json.writeValueAsString(body);
                builder.contentType(MediaType.APPLICATION_JSON).content(content);
            }
            if (csrf != null) {
                builder.header(CSRF_HEADER, csrf);
            }
            return builder;
        }

        private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
            builder.accept(MediaType.APPLICATION_JSON);
            if (session != null) {
                builder.session(session);
            }
            if (csrf != null) {
                builder.cookie(new Cookie(CSRF_COOKIE, csrf));
            }
            MvcResult result = mvc.perform(builder).andReturn();
            // MockHttpServletRequest answers null once the session was invalidated (logout, dropped session).
            session = result.getRequest().getSession(false) instanceof MockHttpSession s && !s.isInvalid() ? s : null;
            Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
            if (cookie != null) {
                csrf = cookie.getValue() == null || cookie.getValue().isEmpty() ? null : cookie.getValue();
            }
            return result;
        }

        /** Obtains the CSRF cookie and signs in. */
        public MvcResult login(String login, String password) throws Exception {
            if (csrf == null) {
                get("/app/auth/me");
            }
            return post("/app/auth/login", new LoginBody(login, password));
        }
    }

    protected record LoginBody(String login, String password) {
    }

    protected Client client() {
        return new Client();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? json.nullNode() : json.readTree(text);
    }

    protected Firma mainFirma() {
        return firmas.findByShortCode("MAIN").orElseThrow();
    }

    protected AppUser owner() {
        return users.findByLoginAndDeletedFalse(OWNER_LOGIN).orElseThrow();
    }

    /** Creates (or replaces) a test user with a known password in the given firma. */
    protected AppUser createUser(String login, String password, Role role, Long firmaId, boolean active) {
        String normalized = login.toLowerCase(Locale.ROOT);
        users.findByLoginAndDeletedFalse(normalized).ifPresent(users::delete);
        AppUser user = new AppUser();
        user.setFirmaId(firmaId);
        user.setLogin(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setFirstName("Test");
        user.setLastName(role.name().toLowerCase(Locale.ROOT));
        user.setLang("en");
        user.setActive(active);
        return users.save(user);
    }
}
