package com.rutera.core;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.AuditLog;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.UserPrivilege;
import com.rutera.common.repository.AuditLogRepository;
import com.rutera.common.repository.UserPrivilegeRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /app/admin/users} and {@code PUT /app/me/password}: lifecycle, hierarchy, self-protection, audit. */
class UserAdminTest extends AbstractIntegrationTest {

    @Autowired
    AuditLogRepository auditLogs;
    @Autowired
    UserPrivilegeRepository userPrivileges;

    @Test
    void ownerCreatesUpdatesResetsUnlocksAndDeletesAUser() throws Exception {
        Client owner = client();
        assertThat(owner.login(OWNER_LOGIN, OWNER_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        MvcResult created = owner.post("/app/admin/users", Map.of(
                "login", " Disp.One@Test.local ", "firstName", "Piotr", "lastName", "Nowak",
                "role", "dispatcher", "phone", "+48 600 000 000", "password", "Dispatcher-1!"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode user = body(created);
        long id = user.get("id").asLong();
        assertThat(user.get("login").asString()).isEqualTo("disp.one@test.local");
        assertThat(user.get("name").asString()).isEqualTo("Piotr Nowak");
        assertThat(user.get("role").asString()).isEqualTo("DISPATCHER");
        assertThat(user.get("language").asString()).as("firma language by default").isEqualTo("pl");
        assertThat(user.get("active").asBoolean()).isTrue();
        assertThat(user.get("locked").asBoolean()).isFalse();
        assertThat(user.get("createdAt").isString()).isTrue();

        MvcResult list = owner.get("/app/admin/users");
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        List<Long> ids = body(list).valueStream().map(n -> n.get("id").asLong()).toList();
        assertThat(ids).contains(id, owner().getId());

        MvcResult duplicate = owner.post("/app/admin/users", Map.of("login", "DISP.ONE@test.local",
                "firstName", "X", "role", "DRIVER", "password", "Driver-Pass-1"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(duplicate).get("details").get("login").asString()).isEqualTo("admin.user.login_taken");

        Client disp = client();
        MvcResult dispLogin = disp.login("disp.one@test.local", "Dispatcher-1!");
        assertThat(dispLogin.getResponse().getStatus()).isEqualTo(200);
        List<String> privileges = body(dispLogin).get("privileges").valueStream().map(JsonNode::asString).toList();
        assertThat(privileges).contains(Privileges.FLEET_VEHICLE_VIEW).doesNotContain(Privileges.ADMIN_USER_VIEW);
        MvcResult forbidden = disp.get("/app/admin/users");
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(forbidden).get("code").asString()).isEqualTo("auth.access_denied");

        MvcResult updated = owner.put("/app/admin/users/" + id, Map.of("firstName", "Piotr", "lastName", "Nowak",
                "role", "ACCOUNTANT", "language", "en", "active", false));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated).get("role").asString()).isEqualTo("ACCOUNTANT");
        assertThat(body(updated).get("language").asString()).isEqualTo("en");
        assertThat(body(updated).get("active").asBoolean()).isFalse();

        assertThat(disp.get("/app/auth/me").getResponse().getStatus()).as("deactivated: session dropped").isEqualTo(401);
        MvcResult disabled = client().login("disp.one@test.local", "Dispatcher-1!");
        assertThat(body(disabled).get("code").asString()).isEqualTo("auth.login.disabled");

        assertThat(owner.put("/app/admin/users/" + id, Map.of("firstName", "Piotr", "lastName", "Nowak",
                "role", "ACCOUNTANT", "language", "en", "active", true)).getResponse().getStatus()).isEqualTo(200);

        MvcResult shortPassword = owner.put("/app/admin/users/" + id + "/password", Map.of("newPassword", "short"));
        assertThat(shortPassword.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(shortPassword).get("details").get("newPassword").asString()).isEqualTo("validation.too_short");
        assertThat(owner.put("/app/admin/users/" + id + "/password", Map.of("newPassword", "Accountant-Pass-2"))
                .getResponse().getStatus()).isEqualTo(204);
        MvcResult asAccountant = client().login("disp.one@test.local", "Accountant-Pass-2");
        assertThat(asAccountant.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(asAccountant).get("role").asString()).isEqualTo("ACCOUNTANT");

        Client attacker = client();
        for (int i = 0; i < 5; i++) {
            attacker.login("disp.one@test.local", "guess-" + i);
        }
        assertThat(body(client().login("disp.one@test.local", "Accountant-Pass-2")).get("code").asString())
                .isEqualTo("auth.login.locked");
        assertThat(body(owner.get("/app/admin/users/" + id)).get("locked").asBoolean()).isTrue();
        assertThat(owner.post("/app/admin/users/" + id + "/unlock", null).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(owner.get("/app/admin/users/" + id)).get("locked").asBoolean()).isFalse();
        assertThat(client().login("disp.one@test.local", "Accountant-Pass-2").getResponse().getStatus()).isEqualTo(200);

        assertThat(owner.delete("/app/admin/users/" + id).getResponse().getStatus()).isEqualTo(204);
        assertThat(owner.get("/app/admin/users/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(owner.get("/app/admin/users")).valueStream().map(n -> n.get("id").asLong()).toList())
                .doesNotContain(id);
        assertThat(body(client().login("disp.one@test.local", "Accountant-Pass-2")).get("code").asString())
                .isEqualTo("auth.login.invalid");

        List<String> actions = auditLogs.findAll().stream()
                .filter(a -> "admin".equals(a.getModule()) && Long.valueOf(id).equals(a.getEntityId()))
                .map(AuditLog::getAction).toList();
        assertThat(actions).contains("user_created", "user_updated", "user_password_reset", "user_unlocked", "user_deleted");
    }

    @Test
    void selfProtectionHierarchyAndValidation() throws Exception {
        Firma firma = mainFirma();
        Client owner = client();
        owner.login(OWNER_LOGIN, OWNER_PASSWORD);
        long ownerId = owner().getId();

        Map<String, Object> self = new HashMap<>(Map.of("firstName", "Anna", "lastName", "Kowalska",
                "role", "OWNER", "language", "pl", "active", false));
        MvcResult deactivateSelf = owner.put("/app/admin/users/" + ownerId, self);
        assertThat(deactivateSelf.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(deactivateSelf).get("code").asString()).isEqualTo("admin.user.self_forbidden");
        self.put("active", true);
        self.put("role", "DRIVER");
        assertThat(owner.put("/app/admin/users/" + ownerId, self).getResponse().getStatus()).isEqualTo(403);
        assertThat(owner.delete("/app/admin/users/" + ownerId).getResponse().getStatus()).isEqualTo(403);
        assertThat(owner.put("/app/admin/users/" + ownerId + "/password", Map.of("newPassword", "Whatever-Pass-1"))
                .getResponse().getStatus()).isEqualTo(403);
        // own name and phone may be edited through the admin screen
        self.put("role", "OWNER");
        self.put("phone", "+48 500 100 200");
        MvcResult ownEdit = owner.put("/app/admin/users/" + ownerId, self);
        assertThat(ownEdit.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ownEdit).get("phone").asString()).isEqualTo("+48 500 100 200");

        MvcResult badRole = owner.post("/app/admin/users", Map.of("login", "root2@test.local", "firstName", "R",
                "role", "SUPERUSER", "password", "Root-Pass-1234"));
        assertThat(badRole.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(badRole).get("details").get("role").asString()).isEqualTo("validation.invalid_value");
        MvcResult customWithoutRole = owner.post("/app/admin/users", Map.of("login", "custom@test.local",
                "firstName", "C", "role", "CUSTOM", "password", "Custom-Pass-1234"));
        assertThat(body(customWithoutRole).get("details").get("customRoleId").asString()).isEqualTo("validation.required");
        MvcResult badLogin = owner.post("/app/admin/users", Map.of("login", "no spaces allowed", "firstName", "C",
                "role", "DRIVER", "language", "xx", "password", ""));
        JsonNode details = body(badLogin).get("details");
        assertThat(details.get("login").asString()).isEqualTo("validation.invalid_format");
        assertThat(details.get("language").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("password").asString()).isEqualTo("validation.required");

        // Hierarchy: a dispatcher with the manage privilege may not touch owners nor create one.
        AppUser secondOwner = createUser("owner2@test.local", "Owner2-Pass-1", Role.OWNER, firma.getId(), true);
        AppUser manager = createUser("manager@test.local", "Manager-Pass-1", Role.DISPATCHER, firma.getId(), true);
        RequestContext.runAsFirma(firma.getId(), null, () -> {
            for (String key : List.of(Privileges.ADMIN_USER_VIEW, Privileges.ADMIN_USER_MANAGE)) {
                UserPrivilege p = new UserPrivilege();
                p.setUserId(manager.getId());
                p.setPrivilegeKey(key);
                p.setAllowed(true);
                userPrivileges.save(p);
            }
            return null;
        });
        Client dispatcher = client();
        assertThat(dispatcher.login(manager.getLogin(), "Manager-Pass-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(dispatcher.get("/app/admin/users").getResponse().getStatus()).isEqualTo(200);
        MvcResult touchOwner = dispatcher.put("/app/admin/users/" + secondOwner.getId(), Map.of("firstName", "X",
                "lastName", "Y", "role", "OWNER", "language", "en", "active", false));
        assertThat(touchOwner.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(touchOwner).get("code").asString()).isEqualTo("admin.user.role_not_allowed");
        MvcResult createOwner = dispatcher.post("/app/admin/users", Map.of("login", "owner3@test.local",
                "firstName", "O", "role", "OWNER", "password", "Owner3-Pass-1234"));
        assertThat(createOwner.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(createOwner).get("code").asString()).isEqualTo("admin.user.role_not_allowed");
        MvcResult createDriver = dispatcher.post("/app/admin/users", Map.of("login", "driver1@test.local",
                "firstName", "Jan", "lastName", "Kowalski", "role", "DRIVER", "password", "Driver1-Pass-1234"));
        assertThat(createDriver.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(createDriver).get("role").asString()).isEqualTo("DRIVER");

        // Users of another firma are invisible: 404, not 403.
        Firma other = new Firma();
        other.setName("Other Ltd");
        other.setShortCode("OTHER");
        other.setCountry("DE");
        other = firmas.save(other);
        AppUser foreign = createUser("foreign@test.local", "Foreign-Pass-1", Role.OWNER, other.getId(), true);
        assertThat(owner.get("/app/admin/users/" + foreign.getId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(owner.delete("/app/admin/users/" + foreign.getId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(owner.get("/app/admin/users")).valueStream().map(n -> n.get("id").asLong()).toList())
                .doesNotContain(foreign.getId());
        assertThat(owner.get("/app/admin/users/999999").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void ownPasswordChange() throws Exception {
        AppUser user = createUser("pw@test.local", "Old-Pass-1234", Role.DRIVER, mainFirma().getId(), true);
        Client c = client();
        assertThat(c.login(user.getLogin(), "Old-Pass-1234").getResponse().getStatus()).isEqualTo(200);

        MvcResult wrong = c.put("/app/me/password", Map.of("currentPassword", "wrong", "newPassword", "New-Pass-1234"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(wrong).get("details").get("currentPassword").asString()).isEqualTo("auth.password.wrong_current");
        MvcResult tooShort = c.put("/app/me/password", Map.of("currentPassword", "Old-Pass-1234", "newPassword", "short"));
        assertThat(body(tooShort).get("details").get("newPassword").asString()).isEqualTo("validation.too_short");
        MvcResult missing = c.put("/app/me/password", Map.of("currentPassword", "Old-Pass-1234"));
        assertThat(missing.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(missing).get("details").get("newPassword").asString()).isEqualTo("validation.required");

        MvcResult ok = c.put("/app/me/password", Map.of("currentPassword", "Old-Pass-1234", "newPassword", "New-Pass-1234"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).as("session survives").isEqualTo(200);
        assertThat(body(client().login(user.getLogin(), "Old-Pass-1234")).get("code").asString()).isEqualTo("auth.login.invalid");
        assertThat(client().login(user.getLogin(), "New-Pass-1234").getResponse().getStatus()).isEqualTo(200);
        assertThat(auditLogs.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("password_changed");
            assertThat(a.getUserId()).isEqualTo(user.getId());
        });
    }
}
