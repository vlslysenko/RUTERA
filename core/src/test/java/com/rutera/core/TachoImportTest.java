package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import com.rutera.tacho.TachoTestFiles;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * {@code /app/tacho/files}: upload, duplicates, rejections, matching to drivers and vehicles, manual assignment,
 * creation from a file, the journal, deletion and lastDownloadAt. Only the synthetic files of the tacho test-jar
 * are used. Every test runs in a firma of its own: sha256 is unique per firma and the files repeat.
 */
class TachoImportTest extends AbstractIntegrationTest {

    private static final AtomicInteger FIRMAS = new AtomicInteger();

    /** A new firma with a signed-in owner. */
    private Client newFirmaOwner() throws Exception {
        int n = FIRMAS.incrementAndGet();
        Firma firma = new Firma();
        firma.setName("Tacho Test " + n);
        firma.setShortCode("TACHO" + n);
        firma.setCountry("PL");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-tacho" + n + "@test.local", "TachoOwner-1", Role.OWNER, firma.getId(), true);
        Client c = client();
        assertThat(c.login(owner.getLogin(), "TachoOwner-1").getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private record Part(String name, byte[] bytes) {
    }

    private MvcResult upload(Client c, Part... parts) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/app/tacho/files");
        for (Part part : parts) {
            request.file(new MockMultipartFile("files", part.name(), "application/octet-stream", part.bytes()));
        }
        request.accept(MediaType.APPLICATION_JSON).session(c.session)
                .cookie(new Cookie(CSRF_COOKIE, c.csrf)).header(CSRF_HEADER, c.csrf);
        return mvc.perform(request).andReturn();
    }

    private long createDriver(Client c, String cardNumber, String cardValidTo) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Jan");
        body.put("lastName", "Kierowca");
        body.put("cardNumber", cardNumber);
        body.put("cardIssuingCountry", "PL");
        body.put("cardValidTo", cardValidTo);
        MvcResult res = c.post("/app/drivers", body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private long createVehicle(Client c, String plate) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "TRACTOR");
        body.put("plate", plate);
        MvcResult res = c.post("/app/fleet/vehicles", body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    @Test
    void uploadMatchesByCardAndPlateAndKeepsEveryFileApart() throws Exception {
        Client c = newFirmaOwner();
        long driverId = createDriver(c, TachoTestFiles.CARD_NUMBER, "2030-01-01");
        long vehicleId = createVehicle(c, "TEST 123");

        MvcResult res = upload(c,
                new Part("card.ddd", TachoTestFiles.gen1DriverCard()),
                new Part("C:\\downloads\\vu.ddd", TachoTestFiles.gen1VehicleUnit()),
                new Part("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)),
                new Part("company.ddd", TachoTestFiles.companyCard()),
                new Part("card-again.ddd", TachoTestFiles.gen1DriverCard()));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode results = body(res);
        assertThat(results).hasSize(5);

        JsonNode card = results.get(0);
        assertThat(card.get("status").asString()).isEqualTo("IMPORTED");
        assertThat(card.get("file").get("kind").asString()).isEqualTo("DRIVER_CARD");
        assertThat(card.get("file").get("cardNumber").asString()).isEqualTo(TachoTestFiles.CARD_NUMBER);
        assertThat(card.get("file").get("match").asString()).isEqualTo("CARD_NUMBER");
        assertThat(card.get("file").get("driver").get("id").asLong()).isEqualTo(driverId);
        assertThat(card.get("file").get("uploadedBy").get("name").asString()).isNotBlank();

        JsonNode vu = results.get(1);
        assertThat(vu.get("fileName").asString()).isEqualTo("vu.ddd");
        assertThat(vu.get("status").asString()).isEqualTo("IMPORTED");
        assertThat(vu.get("file").get("vin").asString()).isEqualTo(TachoTestFiles.VIN);
        assertThat(vu.get("file").get("match").asString()).isEqualTo("PLATE");
        assertThat(vu.get("file").get("vehicle").get("id").asLong()).isEqualTo(vehicleId);

        assertThat(results.get(2).get("status").asString()).isEqualTo("REJECTED");
        assertThat(results.get(2).get("error").get("code").asString()).isEqualTo("tacho.file.not_tachograph");
        assertThat(results.get(3).get("error").get("code").asString()).isEqualTo("tacho.file.unsupported");
        assertThat(results.get(4).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(results.get(4).get("file").get("id").asLong()).isEqualTo(card.get("file").get("id").asLong());

        // lastDownloadAt of the card and the vehicle; the vehicle got the VIN and the generation from the file.
        JsonNode driver = body(c.get("/app/drivers/" + driverId));
        assertThat(driver.get("card").get("lastDownloadAt").asString())
                .isEqualTo(card.get("file").get("downloadedAt").asString());
        JsonNode vehicle = body(c.get("/app/fleet/vehicles/" + vehicleId));
        assertThat(vehicle.get("vin").asString()).isEqualTo(TachoTestFiles.VIN);
        assertThat(vehicle.get("tachoGen").asString()).isEqualTo("GEN1");
        assertThat(vehicle.get("lastVuDownloadAt").asString()).isEqualTo(vu.get("file").get("downloadedAt").asString());

        // Journal, detail and content.
        JsonNode page = body(c.get("/app/tacho/files"));
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("kind").asString()).isEqualTo("VEHICLE_UNIT");
        assertThat(body(c.get("/app/tacho/files?kind=DRIVER_CARD")).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/tacho/files?vehicleId=" + vehicleId)).get("total").asLong()).isEqualTo(1);
        assertThat(body(c.get("/app/tacho/files?unassigned=true")).get("total").asLong()).isZero();
        long vuId = vu.get("file").get("id").asLong();
        JsonNode detail = body(c.get("/app/tacho/files/" + vuId));
        assertThat(detail.get("originalName").asString()).isEqualTo("vu.ddd");
        assertThat(detail.get("counts").get("activityDays").asInt()).isGreaterThan(0);
        assertThat(detail.get("warnings")).isEmpty();
        assertThat(detail.get("suggestion").isNull()).isTrue();
        MvcResult content = c.get("/app/tacho/files/" + vuId + "/content");
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(TachoTestFiles.gen1VehicleUnit());
        assertThat(content.getResponse().getHeader("Content-Disposition")).contains("vu.ddd");

        // Deleting the only card file empties lastDownloadAt again.
        long cardId = card.get("file").get("id").asLong();
        assertThat(c.delete("/app/tacho/files/" + cardId).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/tacho/files/" + cardId).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(c.get("/app/drivers/" + driverId)).get("card").get("lastDownloadAt").isNull()).isTrue();
        assertThat(body(upload(c, new Part("card.ddd", TachoTestFiles.gen1DriverCard()))).get(0).get("status")
                .asString()).as("a deleted file can be uploaded again").isEqualTo("IMPORTED");
    }

    @Test
    void renewedCardIsFoundByDriverIdentificationAndTakesOverTheCard() throws Exception {
        Client c = newFirmaOwner();
        // Same first 14 characters, other replacement/renewal index, older expiry.
        String oldCard = TachoTestFiles.CARD_NUMBER.substring(0, 14) + "01";
        long driverId = createDriver(c, oldCard, "2001-01-01");
        JsonNode result = body(upload(c, new Part("new-card.ddd", TachoTestFiles.gen2DriverCard()))).get(0);
        assertThat(result.get("status").asString()).isEqualTo("IMPORTED");
        assertThat(result.get("file").get("generation").asString()).isIn("GEN2", "GEN2V2");
        assertThat(result.get("file").get("match").asString()).isEqualTo("DRIVER_IDENTIFICATION");
        assertThat(result.get("file").get("driver").get("id").asLong()).isEqualTo(driverId);
        JsonNode card = body(c.get("/app/drivers/" + driverId)).get("card");
        assertThat(card.get("cardNumber").asString()).isEqualTo(TachoTestFiles.CARD_NUMBER);
        assertThat(card.get("validTo").asString()).isEqualTo(result.get("file").get("cardValidTo").asString());
        assertThat(card.get("lastDownloadAt").asString()).isEqualTo(result.get("file").get("downloadedAt").asString());
    }

    @Test
    void unmatchedFilesAreAssignedByHandOrCreateTheirDriverAndVehicle() throws Exception {
        Client c = newFirmaOwner();
        JsonNode results = body(upload(c,
                new Part("card.ddd", TachoTestFiles.gen1DriverCard()),
                new Part("vu.ddd", TachoTestFiles.gen1VehicleUnit()),
                new Part("vu-overview.ddd", TachoTestFiles.gen1VehicleUnitOverviewOnly())));
        long cardId = results.get(0).get("file").get("id").asLong();
        long vuId = results.get(1).get("file").get("id").asLong();
        long overviewId = results.get(2).get("file").get("id").asLong();
        assertThat(results.get(0).get("file").get("match").asString()).isEqualTo("NONE");
        assertThat(body(c.get("/app/tacho/files?unassigned=true")).get("total").asLong()).isEqualTo(3);

        JsonNode suggestion = body(c.get("/app/tacho/files/" + cardId)).get("suggestion");
        assertThat(suggestion.get("firstName").asString()).isEqualTo("Jan");
        assertThat(suggestion.get("lastName").asString()).isEqualTo("Testowy");
        assertThat(suggestion.get("cardNumber").asString()).isEqualTo(TachoTestFiles.CARD_NUMBER);

        MvcResult createdDriver = c.post("/app/tacho/files/" + cardId + "/driver", null);
        assertThat(createdDriver.getResponse().getStatus()).as(createdDriver.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode driver = body(createdDriver).get("driver");
        assertThat(driver.get("name").asString()).isEqualTo("Jan Testowy");
        assertThat(driver.get("card").get("cardNumber").asString()).isEqualTo(TachoTestFiles.CARD_NUMBER);
        assertThat(driver.get("card").get("lastDownloadAt").isNull()).isFalse();
        assertThat(body(createdDriver).get("file").get("match").asString()).isEqualTo("MANUAL");
        assertThat(c.post("/app/tacho/files/" + cardId + "/driver", null).getResponse().getStatus()).isEqualTo(409);

        MvcResult createdVehicle = c.post("/app/tacho/files/" + vuId + "/vehicle", null);
        assertThat(createdVehicle.getResponse().getStatus()).as(createdVehicle.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode vehicle = body(createdVehicle).get("vehicle");
        assertThat(vehicle.get("plate").asString()).isEqualTo(TachoTestFiles.PLATE);
        assertThat(vehicle.get("vin").asString()).isEqualTo(TachoTestFiles.VIN);
        long vehicleId = vehicle.get("id").asLong();

        MvcResult wrongField = c.put("/app/tacho/files/" + overviewId + "/assignment", Map.of("driverId", 1));
        assertThat(body(wrongField).get("details").get("vehicleId").asString()).isEqualTo("validation.required");
        MvcResult assigned = c.put("/app/tacho/files/" + overviewId + "/assignment", Map.of("vehicleId", vehicleId));
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(assigned).get("match").asString()).isEqualTo("MANUAL");
        assertThat(body(assigned).get("vehicle").get("id").asLong()).isEqualTo(vehicleId);
        assertThat(body(c.get("/app/tacho/files?unassigned=true")).get("total").asLong()).isZero();
    }

    @Test
    void requestLimitsAndPrivileges() throws Exception {
        Client c = newFirmaOwner();
        MvcResult none = upload(c);
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(none).get("details").get("files").asString()).isEqualTo("validation.required");
        Part[] many = new Part[51];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Part("f" + i + ".ddd", new byte[] {1, 2, 3});
        }
        MvcResult tooMany = upload(c, many);
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(413);
        assertThat(body(tooMany).get("code").asString()).isEqualTo("tacho.file.too_many");

        long fileId = body(upload(c, new Part("vu.ddd", TachoTestFiles.gen1VehicleUnit()))).get(0).get("file")
                .get("id").asLong();

        Firma firma = mainFirma();
        AppUser logistician = createUser("logistician-tacho@test.local", "Logi-Pass-77", Role.LOGISTICIAN,
                firma.getId(), true);
        Client viewer = client();
        viewer.login(logistician.getLogin(), "Logi-Pass-77");
        assertThat(viewer.get("/app/tacho/files").getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(viewer, new Part("vu.ddd", TachoTestFiles.gen1VehicleUnit())).getResponse().getStatus())
                .isEqualTo(403);
        // Another firma's file does not exist for this user.
        assertThat(viewer.get("/app/tacho/files/" + fileId).getResponse().getStatus()).isEqualTo(404);

        AppUser driverUser = createUser("driver-tacho@test.local", "Driver-Pass-7", Role.DRIVER, firma.getId(), true);
        Client app = client();
        app.login(driverUser.getLogin(), "Driver-Pass-7");
        assertThat(app.get("/app/tacho/files").getResponse().getStatus()).isEqualTo(403);
        assertThat(List.of(c.delete("/app/tacho/files/" + fileId).getResponse().getStatus())).containsExactly(204);
    }
}
