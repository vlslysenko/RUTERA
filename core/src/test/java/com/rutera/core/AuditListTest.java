package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit journal: the owner reads the firma's entries with their channel (UI, API = via AI), filters work, other
 * roles and other firmas are refused, the operator may read any firma, and the list is also a public API call.
 */
class AuditListTest extends AbstractIntegrationTest {

    @Test
    void ownerReadsTheJournalWithChannels() throws Exception {
        Firma firma = new Firma();
        firma.setName("Audit List Test");
        firma.setShortCode("AUD1");
        firma.setCountry("PL");
        firma.setAiAccess(true);
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-audit@test.local", "AuditOwner-1", Role.OWNER, firma.getId(), true);
        createUser("dispatcher-audit@test.local", "AuditDisp-1", Role.DISPATCHER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "AuditOwner-1").getResponse().getStatus()).isEqualTo(200);

        assertThat(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "AUD 001")).getResponse()
                .getStatus()).isEqualTo(201);
        String token = body(c.post("/app/me/tokens", Map.of("name", "Assistant"))).get("token").asString();
        MvcResult viaApi = mvc.perform(MockMvcRequestBuilders.post("/api/v1/vehicles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "TRACTOR", "plate", "AUD 002")))).andReturn();
        assertThat(viaApi.getResponse().getStatus()).isEqualTo(201);

        JsonNode all = body(c.get("/app/admin/audit?module=fleet"));
        assertThat(all.get("total").asLong()).isEqualTo(2);
        JsonNode newest = all.get("items").get(0);
        assertThat(newest.get("channel").asString()).as("newest first").isEqualTo("API");
        assertThat(newest.get("action").asString()).isEqualTo("vehicle_created");
        assertThat(newest.get("userLogin").asString()).isEqualTo(owner.getLogin());
        assertThat(newest.get("entityType").asString()).isEqualTo("vehicle");
        assertThat(newest.get("summary").asString()).contains("AUD 002");
        assertThat(all.get("items").get(1).get("channel").asString()).isEqualTo("UI");

        JsonNode api = body(c.get("/app/admin/audit?channel=API"));
        Set<String> channels = new HashSet<>();
        api.get("items").forEach(i -> channels.add(i.get("channel").asString()));
        assertThat(channels).containsExactly("API");
        assertThat(body(c.get("/app/admin/audit?module=fleet&q=001")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/admin/audit?userId=" + owner.getId() + "&module=api")).get("items").get(0)
                .get("action").asString()).isEqualTo("api_token_created");
        assertThat(body(c.get("/app/admin/audit?from=2000-01-01&to=2000-01-31")).get("total").asLong()).isZero();
        assertThat(body(c.get("/app/admin/audit?channel=FAX")).get("details").get("channel").asString())
                .isEqualTo("validation.invalid_value");
        assertThat(c.get("/app/admin/audit?firmaId=" + mainFirma().getId()).getResponse().getStatus())
                .as("another firma").isEqualTo(403);

        MvcResult viaToken = mvc.perform(MockMvcRequestBuilders.get("/api/v1/audit?channel=UI")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(viaToken.getResponse().getStatus()).as("also a public API call").isEqualTo(200);
        assertThat(body(viaToken).get("total").asLong()).isGreaterThanOrEqualTo(2);

        Client d = client();
        assertThat(d.login("dispatcher-audit@test.local", "AuditDisp-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/admin/audit").getResponse().getStatus()).isEqualTo(403);

        createUser("operator-audit@test.local", "AuditOper-1", Role.SUPERUSER, null, true);
        Client op = client();
        assertThat(op.login("operator-audit@test.local", "AuditOper-1").getResponse().getStatus()).isEqualTo(200);
        JsonNode ofFirma = body(op.get("/app/admin/audit?module=fleet&firmaId=" + firma.getId()));
        assertThat(ofFirma.get("total").asLong()).isEqualTo(2);
        assertThat(ofFirma.get("items").get(0).get("firmaId").asLong()).isEqualTo(firma.getId());
        assertThat(body(op.get("/app/admin/audit?size=1")).get("total").asLong()).as("all firmas")
                .isGreaterThan(ofFirma.get("total").asLong());
    }
}
