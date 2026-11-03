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
