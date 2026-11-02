package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

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
        assertThat(body(c.put("/app/drivers/" + other, driver("Piotr", "Inny", owner.getId())))
                .get("details").get("userId").asString()).as("only DRIVER logins").isEqualTo("validation.invalid_value");

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

    private MvcResult upload(Client c, String path, byte[] bytes, String name, String kind) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(path);
        request.file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        request.param("kind", kind);
        request.accept(MediaType.APPLICATION_JSON).session(c.session)
                .cookie(new Cookie(CSRF_COOKIE, c.csrf)).header(CSRF_HEADER, c.csrf);
        return mvc.perform(request).andReturn();
    }

    @Test
    void absenceRequestsPayAndTripFiles() throws Exception {
        Firma firma = new Firma();
        firma.setName("Driver App Test 2");
        firma.setShortCode("DAPP2");
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-dapp2@test.local", "DappOwner-2", Role.OWNER, firma.getId(), true);
        AppUser login = createUser("kierowca-dapp2@test.local", "DappDriver-2", Role.DRIVER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "DappOwner-2").getResponse().getStatus()).isEqualTo(200);
        long own = id(c.post("/app/drivers", driver("Anna", "Własna", login.getId())));
        long other = id(c.post("/app/drivers", driver("Ewa", "Obca", null)));
        Client d = client();
        assertThat(d.login(login.getLogin(), "DappDriver-2").getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> vacation = Map.of("kind", "VACATION", "fromDate", "2026-12-21", "toDate", "2026-12-23");
        JsonNode request = body(d.post("/app/drivers/" + own + "/absences", vacation));
        assertThat(request.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(request.get("approvedBy").isNull()).isTrue();
        long requestId = request.get("id").asLong();
        assertThat(d.post("/app/drivers/" + other + "/absences", vacation).getResponse().getStatus()).isEqualTo(403);
        assertThat(body(c.get("/app/absences?status=REQUESTED")).size()).isEqualTo(1);
        JsonNode days = body(d.get("/app/drivers/" + own + "/days?month=2026-12"));
        assertThat(days.toString()).as("a request is no absence yet").doesNotContain("\"ABSENCE\"");

        JsonNode approved = body(c.patch("/app/drivers/" + own + "/absences/" + requestId,
                Map.of("status", "APPROVED", "note", "OK, udanych świąt")));
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("decisionNote").asString()).isEqualTo("OK, udanych świąt");
        assertThat(approved.get("approvedBy").get("id").asLong()).isEqualTo(owner.getId());
        assertThat(body(d.get("/app/drivers/" + own + "/days?month=2026-12")).toString()).contains("\"ABSENCE\"");
        assertThat(d.delete("/app/drivers/" + own + "/absences/" + requestId).getResponse().getStatus())
                .as("decided requests stay").isEqualTo(409);
        assertThat(d.patch("/app/drivers/" + own + "/absences/" + requestId, Map.of("status", "REJECTED"))
                .getResponse().getStatus()).isEqualTo(403);

        Map<String, Object> sick = Map.of("kind", "SICK", "fromDate", "2027-01-05", "toDate", "2027-01-06");
        long second = id(d.post("/app/drivers/" + own + "/absences", sick));
        assertThat(body(c.patch("/app/drivers/" + own + "/absences/" + second, Map.of("status", "REJECTED")))
                .get("status").asString()).isEqualTo("REJECTED");
        long third = id(d.post("/app/drivers/" + own + "/absences", sick));
        assertThat(d.delete("/app/drivers/" + own + "/absences/" + third).getResponse().getStatus()).isEqualTo(204);

        assertThat(d.get("/app/drivers/" + own + "/payments").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.get("/app/admin/firma").getResponse().getStatus()).isEqualTo(403);
        JsonNode settings = body(c.get("/app/admin/firma"));
        assertThat(settings.get("driversSeePay").asBoolean()).isFalse();
        Map<String, Object> change = new HashMap<>();
        change.put("name", settings.get("name").asString());
        change.put("lang", "pl");
        change.put("timezone", "Europe/Warsaw");
        change.put("baseCurrency", "pln");
        change.put("driversSeePay", true);
        JsonNode saved = body(c.put("/app/admin/firma", change));
        assertThat(saved.get("baseCurrency").asString()).isEqualTo("PLN");
        assertThat(saved.get("driversSeePay").asBoolean()).isTrue();
        change.put("timezone", "Mars/Olympus");
        assertThat(body(c.put("/app/admin/firma", change)).get("details").get("timezone").asString())
                .isEqualTo("validation.invalid_value");
        assertThat(d.get("/app/drivers/" + own + "/payments").getResponse().getStatus()).isEqualTo(200);
        assertThat(d.get("/app/drivers/" + other + "/payments").getResponse().getStatus()).isEqualTo(403);
        assertThat(body(d.get("/app/auth/me")).get("firma").get("driversSeePay").asBoolean()).isTrue();

        long vehicle = id(c.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "DA 2002")));
        Map<String, Object> tripBody = new HashMap<>(Map.of("vehicleId", vehicle, "mainDriverId", own,
                "orderIds", List.of(), "stops", List.of()));
        long tripId = id(c.post("/app/trips", tripBody));
        long otherTrip = id(c.post("/app/trips", new HashMap<>(Map.of("vehicleId", vehicle, "mainDriverId", other,
                "orderIds", List.of(), "stops", List.of()))));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        MvcResult uploaded = upload(d, "/app/trips/" + tripId + "/files", png, "cmr.png", "CMR_PHOTO");
        long fileId = id(uploaded);
        assertThat(body(uploaded).get("contentType").asString()).isEqualTo("image/png");
        assertThat(body(uploaded).get("uploadedBy").get("id").asLong()).isEqualTo(login.getId());
        MvcResult text = upload(d, "/app/trips/" + tripId + "/files", "hello".getBytes(), "note.txt", "OTHER");
        assertThat(body(text).get("details").get("file").asString()).isEqualTo("trip.file.type");
        assertThat(upload(d, "/app/trips/" + otherTrip + "/files", png, "x.png", "OTHER").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(body(c.get("/app/trips/" + tripId + "/files")).size()).as("the office sees it").isEqualTo(1);
        MvcResult content = d.get("/app/trips/" + tripId + "/files/" + fileId + "/content");
        assertThat(content.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(d.get("/app/trips/" + otherTrip + "/files").getResponse().getStatus()).isEqualTo(403);
        assertThat(d.delete("/app/trips/" + tripId + "/files/" + fileId).getResponse().getStatus()).isEqualTo(204);

        tripBody.put("status", "DONE");
        assertThat(c.put("/app/trips/" + tripId, tripBody).getResponse().getStatus()).isEqualTo(200);
        MvcResult closed = upload(d, "/app/trips/" + tripId + "/files", png, "late.png", "RECEIPT");
        assertThat(closed.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(closed).get("code").asString()).isEqualTo("trip.file.trip_closed");
        assertThat(upload(c, "/app/trips/" + tripId + "/files", png, "office.png", "RECEIPT").getResponse()
                .getStatus()).as("the office still can").isEqualTo(201);
    }
}
