package com.rutera.core;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.UserPrivilege;
import com.rutera.common.repository.UserPrivilegeRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code @RequiresPrivilege} with per-user overrides, superuser bypass, and the fail-closed tenant filter. */
class PrivilegeAndTenantTest extends AbstractIntegrationTest {

    @Autowired
    UserPrivilegeRepository userPrivileges;

    @Test
    void overrideRemovesPrivilegeAndAspectAnswers403() throws Exception {
        Firma firma = mainFirma();
        AppUser user = createUser("nopriv@test.local", "NoPriv-Pass-1", Role.DISPATCHER, firma.getId(), true);
        UserPrivilege forbid = RequestContext.runAsFirma(firma.getId(), null, () -> {
            UserPrivilege p = new UserPrivilege();
            p.setUserId(user.getId());
            p.setPrivilegeKey(Privileges.PROFILE_SELF_MANAGE);
            p.setAllowed(false);
            return userPrivileges.save(p);
        });
        assertThat(forbid.getFirmaId()).isEqualTo(firma.getId());

        Client c = client();
        assertThat(c.login(user.getLogin(), "NoPriv-Pass-1").getResponse().getStatus()).isEqualTo(200);
        MvcResult denied = c.put("/app/me/settings", Map.of("language", "en"));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asString()).isEqualTo("auth.access_denied");
        // Reading own data is still fine.
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).isEqualTo(200);

        RequestContext.runAsFirma(firma.getId(), null, () -> {
            userPrivileges.delete(forbid);
            return null;
        });
        // GET /app/auth/me refreshes the privilege snapshot of the session.
        assertThat(c.get("/app/auth/me").getResponse().getStatus()).isEqualTo(200);
        MvcResult allowed = c.put("/app/me/settings", Map.of("language", "en"));
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void superuserHasNoFirmaAndBypassesPrivilegeChecks() throws Exception {
        AppUser root = createUser("root@test.local", "Root-Pass-1", Role.SUPERUSER, null, true);
        Client c = client();
        MvcResult login = c.login(root.getLogin(), "Root-Pass-1");
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(login).get("role").asString()).isEqualTo("SUPERUSER");
        assertThat(body(login).get("firma").isNull()).isTrue();

        MvcResult settings = c.put("/app/me/settings", Map.of("startScreen", "fleet"));
        assertThat(settings.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(settings).get("startScreen").asString()).isEqualTo("fleet");
    }

    @Test
    void tenantFilterFailsClosedWithoutContext() throws Exception {
        Firma firma = mainFirma();
        AppUser user = createUser("tenant@test.local", "Tenant-Pass-1", Role.ACCOUNTANT, firma.getId(), true);
        RequestContext.runAsFirma(firma.getId(), null, () -> {
            UserPrivilege p = new UserPrivilege();
            p.setUserId(user.getId());
            p.setPrivilegeKey(Privileges.REPORT_VIEW);
            p.setAllowed(true);
            return userPrivileges.save(p);
        });

        RequestContext.clear();
        List<UserPrivilege> noContext = userPrivileges.findByUserId(user.getId());
        assertThat(noContext).as("no request context: the tenant filter must match nothing").isEmpty();

        List<UserPrivilege> otherFirma = RequestContext.runAsFirma(999_999L, null,
                () -> userPrivileges.findByUserId(user.getId()));
        assertThat(otherFirma).isEmpty();

        List<UserPrivilege> ownFirma = RequestContext.runAsFirma(firma.getId(), null,
                () -> userPrivileges.findByUserId(user.getId()));
        assertThat(ownFirma).hasSize(1);

        List<UserPrivilege> asRoot = RequestContext.runAsRoot(null, () -> userPrivileges.findByUserId(user.getId()));
        assertThat(asRoot).hasSize(1);
    }
}
