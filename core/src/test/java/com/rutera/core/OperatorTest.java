package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.core.bootstrap.OperatorBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator console: only a SUPERUSER lists and changes the firmas' options; an inactive or expired firma cannot sign
 * in; the operator account is created at start from the environment once.
 */
class OperatorTest extends AbstractIntegrationTest {

    @Test
    void operatorChangesThePaidOptionsOfAFirma() throws Exception {
        Firma firma = new Firma();
        firma.setName("Operator Test Haulage");
        firma.setShortCode("OPT1");
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-operator@test.local", "OpOwner-123", Role.OWNER, firma.getId(), true);
        createUser("operator@test.local", "Operator-123", Role.SUPERUSER, null, true);

        Client o = client();
        assertThat(o.login(owner.getLogin(), "OpOwner-123").getResponse().getStatus()).isEqualTo(200);
        assertThat(o.get("/app/operator/firmas").getResponse().getStatus()).as("owners are no operators")
                .isEqualTo(403);

        Client op = client();
        MvcResult login = op.login("operator@test.local", "Operator-123");
        assertThat(body(login).get("role").asString()).isEqualTo("SUPERUSER");
        assertThat(body(login).get("firma").isNull()).isTrue();
        JsonNode page = body(op.get("/app/operator/firmas?q=opt1"));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("shortCode").asString()).isEqualTo("OPT1");
        assertThat(item.get("users").asLong()).isEqualTo(1);
        assertThat(item.get("vehicles").asLong()).isZero();
        assertThat(item.get("aiAccess").asBoolean()).isFalse();
        assertThat(body(op.get("/app/operator/firmas")).get("total").asLong()).isGreaterThan(1);

        Map<String, Object> input = new HashMap<>();
        input.put("plan", "pro");
        input.put("vehicleLimit", 40);
        input.put("aiAccess", true);
        input.put("active", true);
        JsonNode changed = body(op.put("/app/operator/firmas/" + firma.getId(), input));
        assertThat(changed.get("plan").asString()).isEqualTo("PRO");
        assertThat(changed.get("vehicleLimit").asInt()).isEqualTo(40);
        assertThat(changed.get("aiAccess").asBoolean()).isTrue();
        assertThat(body(o.post("/app/me/tokens", Map.of("name", "Now allowed"))).get("token").asString())
                .as("the owner can create API tokens now").startsWith("rtr_");

        input.put("vehicleLimit", -1);
        input.remove("active");
        JsonNode invalid = body(op.put("/app/operator/firmas/" + firma.getId(), input));
        assertThat(invalid.get("details").get("vehicleLimit").asString()).isEqualTo("validation.invalid_value");
        assertThat(invalid.get("details").get("active").asString()).isEqualTo("validation.required");

        // An expired firma cannot sign in any more.
        input.put("vehicleLimit", 40);
        input.put("active", true);
        input.put("expiresAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        assertThat(op.put("/app/operator/firmas/" + firma.getId(), input).getResponse().getStatus()).isEqualTo(200);
        MvcResult blocked = client().login(owner.getLogin(), "OpOwner-123");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(blocked).get("code").asString()).isEqualTo("auth.login.firma_inactive");
        input.put("expiresAt", null);
        assertThat(body(op.put("/app/operator/firmas/" + firma.getId(), input)).get("expiresAt").isNull()).isTrue();
        assertThat(client().login(owner.getLogin(), "OpOwner-123").getResponse().getStatus()).isEqualTo(200);
        assertThat(op.get("/app/operator/firmas/999999").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void operatorOnboardsAFirmaWithItsOwner() throws Exception {
        createUser("operator2@test.local", "Operator-123", Role.SUPERUSER, null, true);
        users.findByLoginAndDeletedFalse("owner-new@test.local").ifPresent(users::delete);
        Client op = client();
        assertThat(op.login("operator2@test.local", "Operator-123").getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> owner = new HashMap<>();
        owner.put("login", "Owner-New@test.local");
        owner.put("firstName", "Nowa");
        owner.put("lastName", "Właścicielka");
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Nowa Spedycja");
        input.put("shortCode", "nowa-1");
        input.put("country", "pl");
        input.put("lang", "pl");
        input.put("timezone", "Europe/Warsaw");
        input.put("baseCurrency", "pln");
        input.put("aiAccess", true);
        input.put("owner", owner);
        MvcResult created = op.post("/app/operator/firmas", input);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode answer = body(created);
        assertThat(answer.get("firma").get("shortCode").asString()).isEqualTo("NOWA-1");
        assertThat(answer.get("firma").get("plan").asString()).isEqualTo("STANDARD");
        assertThat(answer.get("firma").get("aiAccess").asBoolean()).isTrue();
        assertThat(answer.get("ownerLogin").asString()).isEqualTo("owner-new@test.local");
        String password = answer.get("password").asString();
        assertThat(password).hasSize(16);

        Client newOwner = client();
        MvcResult login = newOwner.login("owner-new@test.local", password);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(login).get("role").asString()).isEqualTo("OWNER");
        assertThat(body(login).get("firma").get("timezone").asString()).isEqualTo("Europe/Warsaw");
        assertThat(newOwner.post("/app/operator/firmas", input).getResponse().getStatus()).isEqualTo(403);

        JsonNode again = body(op.post("/app/operator/firmas", input));
        assertThat(again.get("details").get("shortCode").asString()).isEqualTo("operator.firma.short_code_taken");
        assertThat(again.get("details").get("owner.login").asString()).isEqualTo("admin.user.login_taken");
        input.put("shortCode", "NOWA-2");
        input.put("timezone", "Mars/Base");
        owner.put("login", "owner-new2@test.local");
        owner.put("password", "short");
        JsonNode invalid = body(op.post("/app/operator/firmas", input));
        assertThat(invalid.get("details").get("timezone").asString()).isEqualTo("validation.invalid_value");
        assertThat(invalid.get("details").get("owner.password").asString()).isEqualTo("validation.too_short");
        input.put("timezone", "UTC");
        owner.put("password", "Given-Password-1");
        JsonNode given = body(op.post("/app/operator/firmas", input));
        assertThat(given.get("password").isNull()).as("a given password is not echoed").isTrue();
        assertThat(client().login("owner-new2@test.local", "Given-Password-1").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void operatorAccountFromTheEnvironment() {
        users.findByLoginAndDeletedFalse("ops@test.local").ifPresent(users::delete);
        new OperatorBootstrap(users, passwordEncoder, " Ops@Test.local ", "Ops-Password-1").run(null);
        AppUser created = users.findByLoginAndDeletedFalse("ops@test.local").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.SUPERUSER);
        assertThat(created.getFirmaId()).isNull();
        assertThat(passwordEncoder.matches("Ops-Password-1", created.getPasswordHash())).isTrue();
        new OperatorBootstrap(users, passwordEncoder, "ops@test.local", "Other-Password-2").run(null);
        assertThat(passwordEncoder.matches("Ops-Password-1",
                users.findByLoginAndDeletedFalse("ops@test.local").orElseThrow().getPasswordHash()))
                .as("an existing login is left alone").isTrue();
        new OperatorBootstrap(users, passwordEncoder, "", "").run(null);
    }
}
