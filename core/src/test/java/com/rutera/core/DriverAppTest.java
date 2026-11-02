package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The driver cabinet: a login linked to a driver sees that driver in /app/auth/me and reads only its own driver,
 * trips, hours, documents, absences and working days, marks stops of its own trips; everything else is 403.
 */
class DriverAppTest extends AbstractIntegrationTest {

    private long id(MvcResult res) throws Exception {
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isIn(200, 201);
        return body(res).get("id").asLong();
    }

    private static Map<String, Object> driver(String first, String last, Long userId) {
        Map<String, Object> d = new HashMap<>();
        d.put("firstName", first);
        d.put("lastName", last);
        d.put("userId", userId);
        return d;
    }

    @Test
    void aDriverSeesOnlyTheirOwnData() throws Exception {
        Firma firma = new Firma();
        firma.setName("Driver App Test");
        firma.setShortCode("DAPP1");
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-dapp@test.local", "DappOwner-1", Role.OWNER, firma.getId(), true);
        AppUser login = createUser("kierowca-dapp@test.local", "DappDriver-1", Role.DRIVER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "DappOwner-1").getResponse().getStatus()).isEqualTo(200);

        long own = id(c.post("/app/drivers", driver("Jan", "Własny", null)));
        long other = id(c.post("/app/drivers", driver("Piotr", "Inny", null)));
        JsonNode linked = body(c.put("/app/drivers/" + own, driver("Jan", "Własny", login.getId())));
        assertThat(linked.get("user").get("id").asLong()).isEqualTo(login.getId());
        MvcResult taken = c.put("/app/drivers/" + other, driver("Piotr", "Inny", login.getId()));
        assertThat(body(taken).get("details").get("userId").asString()).isEqualTo("driver.user.taken");
        assertThat(body(c.put("/app/drivers/" + other, driver("Piotr", "Inny", owner.getId() + 999_999)))
                .get("details").get("userId").asString()).isEqualTo("validation.invalid_value");

        long vehicle = id(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "DA 1001")));
        Map<String, Object> stop = new HashMap<>(Map.of("kind", "LOADING", "address", "Poznań", "country", "PL"));
        Map<String, Object> ownTrip = new HashMap<>(Map.of("vehicleId", vehicle, "mainDriverId", own,
                "orderIds", List.of(), "stops", List.of(stop)));
        JsonNode trip = body(c.post("/app/trips", ownTrip));
        long tripId = trip.get("id").asLong();
        long stopId = trip.get("stops").get(0).get("id").asLong();
        Map<String, Object> otherTrip = new HashMap<>(Map.of("vehicleId", vehicle, "mainDriverId", other,
                "orderIds", List.of(), "stops", List.of()));
        long otherTripId = id(c.post("/app/trips", otherTrip));

        Client d = client();
        assertThat(d.login(login.getLogin(), "DappDriver-1").getResponse().getStatus()).isEqualTo(200);
        JsonNode me = body(d.get("/app/auth/me"));
        assertThat(me.get("driver").get("id").asLong()).isEqualTo(own);
        assertThat(me.get("driver").get("name").asString()).contains("Własny");
        assertThat(body(c.get("/app/auth/me")).get("driver").isNull()).as("the owner is no driver").isTrue();

        assertThat(d.get("/app/drivers/" + own).getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/drivers/" + other).getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/drivers").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/drivers/" + own + "/documents").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/drivers/" + own + "/absences").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/drivers/" + own + "/days?month=2026-11").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/drivers/" + own + "/payments").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/drivers/" + other + "/absences").getResponse().getStatus()).isEqualTo(403);

        JsonNode trips = body(d.get("/app/trips?driverId=" + own));
        assertThat(trips.get("total").asLong()).isEqualTo(1);
        assertThat(d.get("/app/trips").getResponse().getStatus()).as("driverId is required").isEqualTo(403);
        assertThat(d.get("/app/trips?driverId=" + other).getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/trips/" + tripId).getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/trips/" + otherTripId).getResponse().getStatus()).isEqualTo(403);
        assertThat(body(d.patch("/app/trips/" + tripId + "/stops/" + stopId, Map.of("status", "DONE")))
                .get("status").asString()).isEqualTo("DONE");
        assertThat(d.patch("/app/trips/" + tripId + "/stops/" + stopId, Map.of("status", "PLANNED")).getResponse()
                .getStatus()).isEqualTo(422);
        assertThat(d.put("/app/trips/" + tripId, ownTrip).getResponse().getStatus()).isEqualTo(403);

        assertThat(d.get("/app/tacho/activity?driverId=" + own + "&from=2026-11-01&to=2026-11-02").getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(d.get("/app/tacho/activity?driverId=" + other + "&from=2026-11-01&to=2026-11-02").getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(d.get("/app/tacho/violations?driverId=" + own).getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/tacho/violations").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/invoices").getResponse().getStatus()).isEqualTo(403);
    }
}
