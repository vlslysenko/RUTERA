package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /app/fleet/vehicles}: CRUD, plate rules, trailers, documents, privileges and firma isolation. */
class FleetVehicleTest extends AbstractIntegrationTest {

    private static Map<String, Object> vehicle(String kind, String plate) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("plate", plate);
        body.put("tachoGen", "GEN2");
        body.put("status", "ACTIVE");
        return body;
    }

    private Client ownerClient() throws Exception {
        Client c = client();
        assertThat(c.login(OWNER_LOGIN, OWNER_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long create(Client c, Map<String, Object> body) throws Exception {
        MvcResult res = c.post("/app/fleet/vehicles", body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private static List<String> plates(JsonNode list) {
        List<String> plates = new ArrayList<>();
        list.forEach(v -> plates.add(v.get("plate").asString()));
        return plates;
    }

    @Test
    void ownerCreatesReadsUpdatesAndDeletesVehicles() throws Exception {
        Client c = ownerClient();
        long trailer = create(c, vehicle("SEMI_TRAILER", "wgm 7001p"));
        Map<String, Object> tractorBody = vehicle("TRACTOR", "  wgm  7000a ");
        tractorBody.put("vin", "wdb 96340310123456");
        tractorBody.put("make", "Mercedes-Benz");
        tractorBody.put("model", "Actros 1845");
        tractorBody.put("euroClass", "EURO_6");
        tractorBody.put("axles", 2);
        tractorBody.put("grossWeightKg", 18000);
        tractorBody.put("heightMm", 4000);
        tractorBody.put("avgL100km", 29.5);
        tractorBody.put("fuelType", "DIESEL");
        tractorBody.put("odometerKm", 412000);
        tractorBody.put("defaultTrailerId", trailer);
        MvcResult created = c.post("/app/fleet/vehicles", tractorBody);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode tractor = body(created);
        long tractorId = tractor.get("id").asLong();
        assertThat(tractor.get("plate").asString()).isEqualTo("WGM 7000A");
        assertThat(tractor.get("vin").asString()).isEqualTo("WDB96340310123456");
        assertThat(tractor.get("avgL100km").decimalValue()).isEqualByComparingTo("29.5");
        assertThat(tractor.get("defaultTrailer").get("id").asLong()).isEqualTo(trailer);
        assertThat(tractor.get("defaultTrailer").get("plate").asString()).isEqualTo("WGM 7001P");
        assertThat(tractor.get("defaultDriver").isNull()).isTrue();
        assertThat(tractor.get("nextDocument").isNull()).isTrue();
        assertThat(tractor.get("createdAt").isNull()).isFalse();

        JsonNode list = body(c.get("/app/fleet/vehicles"));
        List<String> plates = plates(list);
        assertThat(plates).contains("WGM 7000A", "WGM 7001P");
        assertThat(plates.indexOf("WGM 7000A")).isLessThan(plates.indexOf("WGM 7001P"));

        tractorBody.put("status", "SERVICE");
        tractorBody.put("odometerKm", 415500);
        MvcResult updated = c.put("/app/fleet/vehicles/" + tractorId, tractorBody);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated).get("status").asString()).isEqualTo("SERVICE");
        assertThat(body(updated).get("odometerKm").asInt()).isEqualTo(415500);

        // Deleting the trailer detaches it from the tractor and frees its plate.
        assertThat(c.delete("/app/fleet/vehicles/" + trailer).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/fleet/vehicles/" + trailer).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(c.get("/app/fleet/vehicles/" + tractorId)).get("defaultTrailer").isNull()).isTrue();
        assertThat(plates(body(c.get("/app/fleet/vehicles")))).doesNotContain("WGM 7001P");
        create(c, vehicle("SEMI_TRAILER", "WGM-7001P"));
    }

    @Test
    void plateIsUniqueIgnoringCaseSpacesAndHyphens() throws Exception {
        Client c = ownerClient();
        long first = create(c, vehicle("TRUCK", "PO 12345"));
        MvcResult duplicate = c.post("/app/fleet/vehicles", vehicle("VAN", "po-123 45"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(duplicate).get("details").get("plate").asString()).isEqualTo("fleet.vehicle.plate_taken");

        // Saving a vehicle under its own plate is not a conflict; taking another one's plate is.
        long second = create(c, vehicle("VAN", "PO 54321"));
        assertThat(c.put("/app/fleet/vehicles/" + first, vehicle("TRUCK", "PO12345")).getResponse().getStatus())
                .isEqualTo(200);
        MvcResult taken = c.put("/app/fleet/vehicles/" + second, vehicle("VAN", "po 12345"));
        assertThat(taken.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(taken).get("details").get("plate").asString()).isEqualTo("fleet.vehicle.plate_taken");
    }

    @Test
    void invalidInputAnswers422PerField() throws Exception {
        Client c = ownerClient();
        long truck = create(c, vehicle("TRUCK", "KR 1000"));
        Map<String, Object> bad = vehicle("SPACESHIP", "KR 1001");
        bad.put("euroClass", "EURO_9");
        bad.put("axles", 0);
        bad.put("defaultDriverId", 999_999);
        bad.put("defaultTrailerId", truck);
        bad.put("vin", "VIN!");
        MvcResult res = c.post("/app/fleet/vehicles", bad);
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        JsonNode details = body(res).get("details");
        assertThat(details.get("axles").asString()).isEqualTo("validation.invalid_value");

        bad.remove("axles");
        res = c.post("/app/fleet/vehicles", bad);
        details = body(res).get("details");
        assertThat(details.get("kind").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("euroClass").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("vin").asString()).isEqualTo("validation.invalid_format");

        bad.put("kind", "TRACTOR");
        res = c.post("/app/fleet/vehicles", bad);
        details = body(res).get("details");
        assertThat(details.get("defaultDriverId").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("defaultTrailerId").asString()).as("a truck is not a trailer")
                .isEqualTo("validation.invalid_value");

        MvcResult noPlate = c.post("/app/fleet/vehicles", vehicle("TRUCK", "  "));
        assertThat(body(noPlate).get("details").get("plate").asString()).isEqualTo("validation.required");
    }

    @Test
    void trailersDropEngineFields() throws Exception {
        Client c = ownerClient();
        Map<String, Object> body = vehicle("TRAILER", "GD 2000");
        body.put("fuelType", "DIESEL");
        body.put("avgL100km", 30);
        body.put("odometerKm", 100);
        body.put("defaultDriverId", 1);
        MvcResult res = c.post("/app/fleet/vehicles", body);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        JsonNode trailer = body(res);
        assertThat(trailer.get("tachoGen").asString()).isEqualTo("NONE");
        assertThat(trailer.get("fuelType").isNull()).isTrue();
        assertThat(trailer.get("avgL100km").isNull()).isTrue();
        assertThat(trailer.get("odometerKm").isNull()).isTrue();
    }

    @Test
    void documentsGiveTheNextDeadline() throws Exception {
        Client c = ownerClient();
        long id = create(c, vehicle("TRACTOR", "WE 3000"));
        String docs = "/app/fleet/vehicles/" + id + "/documents";
        MvcResult oc = c.post(docs, Map.of("kind", "INSURANCE_OC", "number", "OC/2026/1",
                "validFrom", "2026-01-01", "validTo", "2026-12-31", "cost", 4200.5, "currency", "pln"));
        assertThat(oc.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(oc).get("currency").asString()).isEqualTo("PLN");
        assertThat(body(oc).get("remindDays").asInt()).isEqualTo(30);
        MvcResult inspection = c.post(docs, Map.of("kind", "INSPECTION", "validTo", "2026-11-15", "remindDays", 14));
        long inspectionId = body(inspection).get("id").asLong();
        assertThat(c.post(docs, Map.of("kind", "OTHER")).getResponse().getStatus()).isEqualTo(201);

        JsonNode list = body(c.get(docs));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("kind").asString()).isEqualTo("INSPECTION");
        assertThat(list.get(2).get("validTo").isNull()).isTrue();
        JsonNode next = body(c.get("/app/fleet/vehicles/" + id)).get("nextDocument");
        assertThat(next.get("kind").asString()).isEqualTo("INSPECTION");
        assertThat(next.get("validTo").asString()).isEqualTo("2026-11-15");
        JsonNode inList = null;
        for (JsonNode v : body(c.get("/app/fleet/vehicles"))) {
            if (v.get("id").asLong() == id) {
                inList = v;
            }
        }
        assertThat(inList).isNotNull();
        assertThat(inList.get("nextDocument").get("validTo").asString()).isEqualTo("2026-11-15");

        MvcResult renewed = c.put(docs + "/" + inspectionId, Map.of("kind", "INSPECTION", "validTo", "2027-11-15"));
        assertThat(renewed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(c.get("/app/fleet/vehicles/" + id)).get("nextDocument").get("kind").asString())
                .isEqualTo("INSURANCE_OC");

        MvcResult backwards = c.post(docs, Map.of("kind", "OTHER", "validFrom", "2026-05-01", "validTo", "2026-04-01"));
        assertThat(body(backwards).get("details").get("validTo").asString()).isEqualTo("validation.invalid_value");
        MvcResult noCurrency = c.post(docs, Map.of("kind", "OTHER", "cost", 10));
        assertThat(body(noCurrency).get("details").get("currency").asString()).isEqualTo("validation.required");

        assertThat(c.delete(docs + "/" + inspectionId).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get(docs))).hasSize(2);
        assertThat(c.delete(docs + "/" + inspectionId).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void privilegesDecideWhoMaySeeAndChange() throws Exception {
        Firma firma = mainFirma();
        long id = create(ownerClient(), vehicle("VAN", "LU 4000"));
        AppUser logistician = createUser("logistician-fleet@test.local", "Logi-Pass-12", Role.LOGISTICIAN,
                firma.getId(), true);
        Client viewer = client();
        viewer.login(logistician.getLogin(), "Logi-Pass-12");
        assertThat(viewer.get("/app/fleet/vehicles").getResponse().getStatus()).isEqualTo(200);
        assertThat(viewer.get("/app/fleet/vehicles/" + id + "/documents").getResponse().getStatus()).isEqualTo(200);
        assertThat(viewer.post("/app/fleet/vehicles", vehicle("VAN", "LU 4001")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(viewer.delete("/app/fleet/vehicles/" + id).getResponse().getStatus()).isEqualTo(403);
        assertThat(viewer.post("/app/fleet/vehicles/" + id + "/documents", Map.of("kind", "OTHER"))
                .getResponse().getStatus()).isEqualTo(403);

        AppUser driver = createUser("driver-fleet@test.local", "Driver-Pass-1", Role.DRIVER, firma.getId(), true);
        Client app = client();
        app.login(driver.getLogin(), "Driver-Pass-1");
        assertThat(app.get("/app/fleet/vehicles").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void vehiclesOfAnotherFirmaAreInvisible() throws Exception {
        Firma other = new Firma();
        other.setName("Other Fleet GmbH");
        other.setShortCode("FLEETX");
        other.setCountry("DE");
        other = firmas.save(other);
        AppUser otherOwner = createUser("owner-fleetx@test.local", "OtherOwner-1", Role.OWNER, other.getId(), true);
        Client foreign = client();
        foreign.login(otherOwner.getLogin(), "OtherOwner-1");
        // The same plate is fine in another firma.
        long foreignTrailer = create(foreign, vehicle("TRAILER", "B 5000"));
        long foreignTruck = create(foreign, vehicle("TRUCK", "B 5001"));

        Client c = ownerClient();
        create(c, vehicle("TRUCK", "B 5001"));
        assertThat(plates(body(c.get("/app/fleet/vehicles")))).doesNotContain("B 5000");
        assertThat(c.get("/app/fleet/vehicles/" + foreignTruck).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.put("/app/fleet/vehicles/" + foreignTruck, vehicle("TRUCK", "B 5002")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(c.delete("/app/fleet/vehicles/" + foreignTruck).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.get("/app/fleet/vehicles/" + foreignTruck + "/documents").getResponse().getStatus())
                .isEqualTo(404);
        Map<String, Object> withForeignTrailer = vehicle("TRACTOR", "B 5003");
        withForeignTrailer.put("defaultTrailerId", foreignTrailer);
        MvcResult res = c.post("/app/fleet/vehicles", withForeignTrailer);
        assertThat(body(res).get("details").get("defaultTrailerId").asString()).isEqualTo("validation.invalid_value");
    }
}
