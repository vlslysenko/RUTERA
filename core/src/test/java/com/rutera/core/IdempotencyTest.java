package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays of the driver cabinet's offline outbox with an Idempotency-Key: the same key returns the first result and
 * creates nothing new, other users and requests without a key are unaffected; push is off without VAPID keys.
 */
class IdempotencyTest extends AbstractIntegrationTest {

    private MvcResult postWithKey(Client c, String path, Object body, String key) throws Exception {
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (key != null) {
            post.header("Idempotency-Key", key);
        }
        return c.send(post);
    }

    @Test
    void aReplayedAbsenceRequestIsCreatedOnce() throws Exception {
        Firma firma = new Firma();
        firma.setName("Idempotency Test");
        firma.setShortCode("IDEM1");
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-idem@test.local", "IdemOwner-1", Role.OWNER, firma.getId(), true);
        AppUser login = createUser("driver-idem@test.local", "IdemDriver-1", Role.DRIVER, firma.getId(), true);
        Client office = client();
        assertThat(office.login(owner.getLogin(), "IdemOwner-1").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> d = new HashMap<>();
        d.put("firstName", "Ola");
        d.put("lastName", "Offline");
        d.put("userId", login.getId());
        long driverId = body(office.post("/app/drivers", d)).get("id").asLong();
        Client phone = client();
        assertThat(phone.login(login.getLogin(), "IdemDriver-1").getResponse().getStatus()).isEqualTo(200);
        String path = "/app/drivers/" + driverId + "/absences";
        Map<String, Object> vacation = Map.of("kind", "VACATION", "fromDate", "2027-02-01", "toDate", "2027-02-03");
        String key = UUID.randomUUID().toString();

        MvcResult first = postWithKey(phone, path, vacation, key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        MvcResult again = postWithKey(phone, path, vacation, key);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(body(again).get("id").asLong()).isEqualTo(body(first).get("id").asLong());
        assertThat(body(phone.get(path))).as("created once").hasSize(1);

        Map<String, Object> overlap = Map.of("kind", "SICK", "fromDate", "2027-02-02", "toDate", "2027-02-02");
        String overlapKey = UUID.randomUUID().toString();
        MvcResult refused = postWithKey(phone, path, overlap, overlapKey);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        MvcResult refusedAgain = postWithKey(phone, path, overlap, overlapKey);
        assertThat(refusedAgain.getResponse().getStatus()).as("4xx results are kept too").isEqualTo(422);
        assertThat(refusedAgain.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");

        Map<String, Object> later = Map.of("kind", "VACATION", "fromDate", "2027-03-01", "toDate", "2027-03-01");
        assertThat(postWithKey(office, path, later, key).getResponse().getStatus())
                .as("another user's key is a new request").isEqualTo(201);
        JsonNode conflict = body(postWithKey(phone, "/app/drivers/999999/absences", later, key));
        assertThat(conflict.get("code").asString()).as("the key on another path").isEqualTo("error.conflict");
        assertThat(postWithKey(phone, path, Map.of("kind", "VACATION", "fromDate", "2027-04-01",
                "toDate", "2027-04-01"), null).getResponse().getStatus()).as("no key: as before").isEqualTo(201);
        assertThat(postWithKey(phone, path, Map.of("kind", "VACATION", "fromDate", "2027-05-01",
                "toDate", "2027-05-01"), "not a valid key!").getResponse().getStatus())
                .as("malformed key: as without").isEqualTo(201);
        assertThat(body(phone.get(path))).hasSize(4);

        MvcResult pushKey = phone.get("/app/push/key");
        assertThat(pushKey.getResponse().getStatus()).as("no VAPID keys in this context").isEqualTo(404);
        assertThat(body(pushKey).get("code").asString()).isEqualTo("push.disabled");
    }
}
