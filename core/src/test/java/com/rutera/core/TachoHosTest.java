package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.core.tacho.TachoFactsBackfill;
import com.rutera.tacho.TachoTestFiles;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Steps B and D of the tachograph import: facts from an uploaded card, the driver's activity and days, violations
 * recomputed on changes, acknowledgement, the start-up backfill. Synthetic files only; one firma per test.
 */
class TachoHosTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TachoFactsBackfill backfill;

    private Firma firma;

    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        firma = new Firma();
        firma.setName("Hos Test " + n);
        firma.setShortCode("HOS" + n);
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-hos" + n + "@test.local", "HosOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "HosOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private JsonNode uploadCard(Client c) throws Exception {
        MvcResult res = mvc.perform(multipart("/app/tacho/files")
                .file(new MockMultipartFile("files", "card.ddd", "application/octet-stream",
                        TachoTestFiles.gen1DriverCard()))
                .accept(MediaType.APPLICATION_JSON).session(c.session)
                .cookie(new Cookie(CSRF_COOKIE, c.csrf)).header(CSRF_HEADER, c.csrf)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        return body(res).get(0).get("file");
    }

    private Map<String, Object> driverBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Jan");
        body.put("lastName", "Testowy");
        body.put("cardNumber", TachoTestFiles.CARD_NUMBER);
        body.put("cardIssuingCountry", "PL");
        return body;
    }

    private void insert(long fileId, String activity, String start, String end) {
        jdbc.update("insert into tacho_activity (firma_id, file_id, source, card_number, slot, crew, activity,"
                        + " start_at, end_at, vehicle_plate) values (?, ?, 'CARD', ?, 1, false, ?, ?, ?, 'PO 1001')",
                firma.getId(), fileId, TachoTestFiles.CARD_NUMBER, activity,
                Timestamp.from(Instant.parse(start)), Timestamp.from(Instant.parse(end)));
    }

    @Test
    void cardFactsGiveTheActivityAndDays() throws Exception {
        Client c = newFirmaOwner();
        long driverId = body(c.post("/app/drivers", driverBody())).get("id").asLong();
        JsonNode file = uploadCard(c);
        assertThat(file.get("driver").get("id").asLong()).isEqualTo(driverId);
        Integer rows = jdbc.queryForObject("select count(*) from tacho_activity where file_id = ?", Integer.class,
                file.get("id").asLong());
        assertThat(rows).isGreaterThan(0);

        LocalDate to = LocalDate.parse(file.get("periodTo").asString());
        LocalDate from = to.minusDays(6);
        MvcResult res = c.get("/app/tacho/activity?driverId=" + driverId + "&from=" + from + "&to=" + to);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode activity = body(res);
        assertThat(activity.get("driver").get("name").asString()).isEqualTo("Jan Testowy");
        assertThat(activity.get("timezone").asString()).isEqualTo("UTC");
        assertThat(activity.get("days")).hasSize(7);
        assertThat(activity.get("intervals").size()).isGreaterThan(0);
        JsonNode interval = activity.get("intervals").get(0);
        assertThat(interval.get("activity").asString()).isIn("DRIVING", "WORK", "AVAILABILITY", "REST", "UNKNOWN");
        assertThat(interval.get("slot").asInt()).isIn(1, 2);
        int recorded = 0;
        for (JsonNode d : activity.get("days")) {
            recorded += d.get("drivingMin").asInt() + d.get("workMin").asInt() + d.get("availabilityMin").asInt()
                    + d.get("restMin").asInt();
        }
        assertThat(recorded).isGreaterThan(0);

        MvcResult tooLong = c.get("/app/tacho/activity?driverId=" + driverId + "&from=" + from.minusDays(40)
                + "&to=" + to);
        assertThat(body(tooLong).get("details").get("to").asString()).isEqualTo("validation.invalid_value");
        assertThat(body(c.get("/app/tacho/activity?from=" + from + "&to=" + to)).get("details").get("driverId")
                .asString()).isEqualTo("validation.required");
    }

    @Test
    void violationsAreRecomputedAcknowledgedAndRemovedWithTheFile() throws Exception {
        Client c = newFirmaOwner();
        long driverId = body(c.post("/app/drivers", driverBody())).get("id").asLong();
        long fileId = uploadCard(c).get("id").asLong();

        // Eleven hours of driving with breaks on a day far from the fixture's data, with a known start.
        insert(fileId, "REST", "2030-01-06T18:00:00Z", "2030-01-07T04:00:00Z");
        insert(fileId, "DRIVING", "2030-01-07T04:00:00Z", "2030-01-07T08:30:00Z");
        insert(fileId, "REST", "2030-01-07T08:30:00Z", "2030-01-07T09:15:00Z");
        insert(fileId, "DRIVING", "2030-01-07T09:15:00Z", "2030-01-07T13:45:00Z");
        insert(fileId, "REST", "2030-01-07T13:45:00Z", "2030-01-07T14:30:00Z");
        insert(fileId, "DRIVING", "2030-01-07T14:30:00Z", "2030-01-07T16:30:00Z");
        insert(fileId, "REST", "2030-01-07T16:30:00Z", "2030-01-08T06:00:00Z");
        // Saving the driver recomputes the violations.
        assertThat(c.put("/app/drivers/" + driverId, driverBody()).getResponse().getStatus()).isEqualTo(200);

        JsonNode page = body(c.get("/app/tacho/violations?driverId=" + driverId + "&from=2030-01-01&to=2030-01-31"));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode v = page.get("items").get(0);
        long id = v.get("id").asLong();
        assertThat(v.get("rule").asString()).isEqualTo("DAILY_DRIVING_10H");
        assertThat(v.get("severity").asString()).isEqualTo("SERIOUS");
        assertThat(v.get("actualValue").asInt()).isEqualTo(660);
        assertThat(v.get("limitValue").asInt()).isEqualTo(600);
        assertThat(v.get("vehicle").get("plate").asString()).isEqualTo("PO 1001");
        assertThat(v.get("vehicle").get("id").isNull()).as("not in the fleet").isTrue();
        assertThat(v.get("ack").isNull()).isTrue();

        JsonNode day = body(c.get("/app/tacho/activity?driverId=" + driverId + "&from=2030-01-07&to=2030-01-07"))
                .get("days").get(0);
        assertThat(day.get("drivingMin").asInt()).isEqualTo(660);
        assertThat(day.get("violations").asInt()).isEqualTo(1);
        assertThat(day.get("extDrivingInWeek").asInt()).isEqualTo(1);

        MvcResult acked = c.post("/app/tacho/violations/" + id + "/ack", Map.of("note", "Talked to the driver"));
        assertThat(acked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(acked).get("ack").get("note").asString()).isEqualTo("Talked to the driver");
        assertThat(body(acked).get("ack").get("by").get("name").asString()).isNotBlank();
        assertThat(body(c.get("/app/tacho/violations?unacknowledged=true&from=2030-01-01")).get("total").asLong())
                .isZero();

        // A recomputation keeps the acknowledgement of the same violation.
        c.put("/app/drivers/" + driverId, driverBody());
        assertThat(body(c.get("/app/tacho/violations/" + id)).get("ack").isNull()).isFalse();
        assertThat(body(c.delete("/app/tacho/violations/" + id + "/ack")).get("ack").isNull()).isTrue();

        AppUser logistician = createUser("logistician-hos" + firma.getId() + "@test.local", "Logi-Pass-88",
                Role.LOGISTICIAN, firma.getId(), true);
        Client viewer = client();
        viewer.login(logistician.getLogin(), "Logi-Pass-88");
        assertThat(viewer.get("/app/tacho/violations/" + id).getResponse().getStatus()).isEqualTo(200);
        assertThat(viewer.post("/app/tacho/violations/" + id + "/ack", Map.of()).getResponse().getStatus())
                .isEqualTo(403);
        AppUser driverUser = createUser("driver-hos" + firma.getId() + "@test.local", "Driver-Pass-8", Role.DRIVER,
                firma.getId(), true);
        Client app = client();
        app.login(driverUser.getLogin(), "Driver-Pass-8");
        assertThat(app.get("/app/tacho/violations").getResponse().getStatus()).isEqualTo(403);

        // Deleting the file removes its facts and the violation they caused.
        assertThat(c.delete("/app/tacho/files/" + fileId).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/tacho/violations/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from tacho_activity where file_id = ?", Integer.class, fileId))
                .isZero();
    }

    @Test
    void theBackfillExtractsFilesStoredBeforeTheFacts() throws Exception {
        Client c = newFirmaOwner();
        c.post("/app/drivers", driverBody());
        long fileId = uploadCard(c).get("id").asLong();
        int before = jdbc.queryForObject("select count(*) from tacho_activity where file_id = ?", Integer.class, fileId);
        jdbc.update("delete from tacho_activity where file_id = ?", fileId);
        jdbc.update("update tacho_file set facts_version = 0 where id = ?", fileId);

        backfill.run(null);

        assertThat(jdbc.queryForObject("select count(*) from tacho_activity where file_id = ?", Integer.class, fileId))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select facts_version from tacho_file where id = ?", Integer.class, fileId))
                .isEqualTo(1);
    }
}
