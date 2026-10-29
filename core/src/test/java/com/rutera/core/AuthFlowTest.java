package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.domain.Firma;
import com.rutera.common.repository.AuditLogRepository;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sign-in, session, CSRF, settings, dictionaries and the error contract, end to end through MockMvc. */
class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void anonymousMeIs401AndHandsOutCsrfCookieAndRequestId() throws Exception {
        Client c = client();
        MvcResult me = c.get("/app/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(me).get("code").asString()).isEqualTo("auth.unauthenticated");
        assertThat(body(me).get("message").asString()).isEqualTo("Please sign in");
        assertThat(me.getResponse().getCookie(CSRF_COOKIE)).isNotNull();
        assertThat(me.getResponse().getCookie(CSRF_COOKIE).isHttpOnly()).isFalse();
        assertThat(me.getResponse().getHeader("X-Request-Id")).hasSize(36);
    }

    @Test
    void loginWithoutCsrfTokenIs403() throws Exception {
        Client c = client();
        MvcResult res = c.post("/app/auth/login", new LoginBody(OWNER_LOGIN, OWNER_PASSWORD));
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(res).get("code").asString()).isEqualTo("auth.csrf_invalid");
    }

    @Test
    void loginWithCsrfCookieButNoHeaderIs403() throws Exception {
        Client c = client();
        c.get("/app/auth/me");
        MvcResult res = c.postWithoutCsrfHeader("/app/auth/login", new LoginBody(OWNER_LOGIN, OWNER_PASSWORD));
        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(res).get("code").asString()).isEqualTo("auth.csrf_invalid");
    }

    @Test
    void wrongPasswordAndUnknownLoginAre401Invalid() throws Exception {
        Client c = client();
        MvcResult wrong = c.login(OWNER_LOGIN, "not-the-password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("code").asString()).isEqualTo("auth.login.invalid");
        assertThat(c.session).isNull();

        MvcResult unknown = c.login("nobody@test.local", "whatever");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(unknown).get("code").asString()).isEqualTo("auth.login.invalid");

        MvcResult blank = c.post("/app/auth/login", Map.of("login", "", "password", ""));
        assertThat(blank.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(blank).get("code").asString()).isEqualTo("error.validation");
        assertThat(body(blank).get("details").get("login").asString()).isEqualTo("validation.required");
    }

    @Test
    void loginMeSettingsLogoutFlow() throws Exception {
        Client c = client();
        String csrfBefore = null;
        c.get("/app/auth/me");
        csrfBefore = c.csrf;

        MvcResult login = c.login(OWNER_LOGIN, OWNER_PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode user = body(login);
        assertThat(user.get("login").asString()).isEqualTo(OWNER_LOGIN);
        assertThat(user.get("name").asString()).isEqualTo("Anna Kowalska");
        assertThat(user.get("role").asString()).isEqualTo("OWNER");
        assertThat(user.get("firma").get("name").asString()).isEqualTo("Test Transport");
        assertThat(user.get("language").asString()).isEqualTo("pl");
        assertThat(user.get("startScreen").isNull()).isTrue();
        assertThat(c.session).isNotNull();
        assertThat(c.csrf).isNotEqualTo(csrfBefore);

        MvcResult me = c.get("/app/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).get("id").asLong()).isEqualTo(user.get("id").asLong());

        MvcResult settings = c.put("/app/me/settings", Map.of("language", "de", "startScreen", "map"));
        assertThat(settings.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(settings).get("language").asString()).isEqualTo("de");
        assertThat(body(settings).get("startScreen").asString()).isEqualTo("map");

        MvcResult meAgain = c.get("/app/auth/me");
        assertThat(body(meAgain).get("language").asString()).isEqualTo("de");
        assertThat(body(meAgain).get("startScreen").asString()).isEqualTo("map");

        MvcResult reset = c.put("/app/me/settings", "{\"startScreen\": null}");
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(reset).get("startScreen").isNull()).isTrue();
        assertThat(body(reset).get("language").asString()).isEqualTo("de");

        MvcResult invalid = c.put("/app/me/settings", Map.of("language", "xx", "startScreen", "mars"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(invalid).get("code").asString()).isEqualTo("error.validation");
        assertThat(body(invalid).get("details").get("language").asString()).isEqualTo("validation.invalid_value");
        assertThat(body(invalid).get("details").get("startScreen").asString()).isEqualTo("validation.invalid_value");
        // The error message follows the user's language (German after the change above).
        assertThat(body(invalid).get("message").asString()).isEqualTo("Ungültige Eingabedaten");

        MvcResult unknownPath = c.get("/app/no/such/thing");
        assertThat(unknownPath.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknownPath).get("code").asString()).isEqualTo("error.not_found");

        MvcResult logout = c.post("/app/auth/logout", null);
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(c.session).isNull();

        MvcResult afterLogout = c.get("/app/auth/me");
        assertThat(afterLogout.getResponse().getStatus()).isEqualTo(401);

        // restore the language for the other tests
        Client again = client();
        again.login(OWNER_LOGIN, OWNER_PASSWORD);
        again.put("/app/me/settings", Map.of("language", "pl"));

        List<String> ownerActions = auditLogs.findAll().stream()
                .filter(a -> owner().getId().equals(a.getUserId()))
                .map(AuditLog::getAction)
                .toList();
        assertThat(ownerActions).contains("login", "logout", "settings_changed");
        assertThat(auditLogs.findAll()).allSatisfy(a -> assertThat(a.getRequestId()).isNotBlank());
    }

    @Test
    void accountIsLockedAfterFiveWrongPasswordsEvenForTheRightOne() throws Exception {
        Firma firma = mainFirma();
        AppUser user = createUser("lock@test.local", "Lock-Pass-1", Role.DISPATCHER, firma.getId(), true);
        Client c = client();
        for (int i = 1; i <= 4; i++) {
            MvcResult res = c.login(user.getLogin(), "wrong-" + i);
            assertThat(res.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(res).get("code").asString()).isEqualTo("auth.login.invalid");
        }
        assertThat(users.findById(user.getId()).orElseThrow().getFailedLogins()).isEqualTo(4);

        MvcResult fifth = c.login(user.getLogin(), "wrong-5");
        assertThat(body(fifth).get("code").asString()).isEqualTo("auth.login.invalid");
        AppUser locked = users.findById(user.getId()).orElseThrow();
        assertThat(locked.getLockedUntil()).isNotNull();
        assertThat(locked.getFailedLogins()).isZero();

        MvcResult right = c.login(user.getLogin(), "Lock-Pass-1");
        assertThat(right.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(right).get("code").asString()).isEqualTo("auth.login.locked");

        locked.setLockedUntil(null);
        users.save(locked);
        MvcResult unlocked = c.login(user.getLogin(), "Lock-Pass-1");
        assertThat(unlocked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(unlocked).get("role").asString()).isEqualTo("DISPATCHER");

        List<AuditLog> failures = auditLogs.findAll().stream()
                .filter(a -> user.getId().equals(a.getUserId()) && "login_failed".equals(a.getAction()))
                .toList();
        assertThat(failures).hasSizeGreaterThanOrEqualTo(6);
        // MySQL normalises JSON on storage, so compare parsed values rather than text.
        assertThat(failures).anySatisfy(a -> assertThat(json.readTree(a.getDetails()).get("locked").asBoolean()).isTrue());
    }

    @Test
    void disabledUserAndInactiveFirmaAreRejected() throws Exception {
        Firma firma = mainFirma();
        AppUser disabled = createUser("off@test.local", "Off-Pass-1", Role.ACCOUNTANT, firma.getId(), false);
        MvcResult res = client().login(disabled.getLogin(), "Off-Pass-1");
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(res).get("code").asString()).isEqualTo("auth.login.disabled");

        Firma frozen = new Firma();
        frozen.setName("Frozen Ltd");
        frozen.setShortCode("FROZEN");
        frozen.setCountry("DE");
        frozen.setActive(false);
        frozen = firmas.save(frozen);
        AppUser ofFrozen = createUser("frozen@test.local", "Frozen-Pass-1", Role.OWNER, frozen.getId(), true);
        MvcResult res2 = client().login(ofFrozen.getLogin(), "Frozen-Pass-1");
        assertThat(res2.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(res2).get("code").asString()).isEqualTo("auth.login.firma_inactive");
    }

    @Test
    void sessionOfUserDisabledMeanwhileIsDropped() throws Exception {
        AppUser user = createUser("gone@test.local", "Gone-Pass-1", Role.LOGISTICIAN, mainFirma().getId(), true);
        Client c = client();
        assertThat(c.login(user.getLogin(), "Gone-Pass-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).isEqualTo(200);

        AppUser row = users.findById(user.getId()).orElseThrow();
        row.setActive(false);
        users.save(row);

        MvcResult me = c.get("/app/auth/me");
        assertThat(me.getResponse().getStatus()).isEqualTo(401);
        assertThat(c.session).isNull();
    }

    @Test
    void dictionariesAreServedWithoutSession() throws Exception {
        Client c = client();
        MvcResult pl = c.get("/app/i18n/pl");
        assertThat(pl.getResponse().getStatus()).isEqualTo(200);
        JsonNode dict = body(pl);
        assertThat(dict.get("app.name").asString()).isEqualTo("RUTERA");
        assertThat(dict.get("nav.overview").asString()).isEqualTo("Przegląd");
        assertThat(dict.get("auth.login.invalid").asString()).isEqualTo("Nieprawidłowy login lub hasło");
        assertThat(dict.get("validation.required").asString()).isEqualTo("Pole wymagane");
        assertThat(dict.size()).isGreaterThan(80);

        MvcResult upper = c.get("/app/i18n/UK");
        assertThat(upper.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(upper).get("nav.fleet").asString()).isEqualTo("Автопарк");

        MvcResult unknown = c.get("/app/i18n/xx");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknown).get("code").asString()).isEqualTo("error.not_found");

        // Accept-Language drives the message of anonymous errors.
        MvcResult de = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/app/auth/me").header("Accept-Language", "de-DE,de;q=0.9")).andReturn();
        assertThat(body(de).get("message").asString()).isEqualTo("Bitte anmelden");
    }

    @Test
    void healthIsPublicAndOtherActuatorEndpointsAreNot() throws Exception {
        Client c = client();
        assertThat(c.get("/actuator/health").getResponse().getStatus()).isEqualTo(200);
        assertThat(c.get("/actuator/env").getResponse().getStatus()).isIn(401, 404);
    }
}
