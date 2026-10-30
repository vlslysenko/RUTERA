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

/** {@code /app/drivers}: CRUD, the inline card, documents, the vehicle link, privileges and firma isolation. */
class DriverTest extends AbstractIntegrationTest {

    private static Map<String, Object> driver(String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        return body;
    }

    private Client ownerClient() throws Exception {
        Client c = client();
        assertThat(c.login(OWNER_LOGIN, OWNER_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    private long create(Client c, Map<String, Object> body) throws Exception {
        MvcResult res = c.post("/app/drivers", body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(201);
        return body(res).get("id").asLong();
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(d -> names.add(d.get("name").asString()));
        return names;
    }

    @Test
    void ownerCreatesReadsUpdatesAndDeletesDrivers() throws Exception {
        Client c = ownerClient();
        Map<String, Object> body = driver(" Jan ", "Zieliński");
        body.put("birthDate", "1985-04-12");
        body.put("phone", "+48 600 100 200");
        body.put("email", "jan.zielinski@example.com");
        body.put("country", "pl");
        body.put("employmentFrom", "2020-03-01");
        body.put("notes", "ADR, prefers night shifts");
        body.put("cardNumber", "pl 1234 5678 9012 34");
        body.put("cardIssuingCountry", "pl");
        body.put("cardValidTo", "2029-05-31");
        MvcResult created = c.post("/app/drivers", body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode jan = body(created);
        long id = jan.get("id").asLong();
        assertThat(jan.get("name").asString()).isEqualTo("Jan Zieliński");
        assertThat(jan.get("country").asString()).isEqualTo("PL");
        assertThat(jan.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(jan.get("card").get("cardNumber").asString()).isEqualTo("PL12345678901234");
        assertThat(jan.get("card").get("issuingCountry").asString()).isEqualTo("PL");
        assertThat(jan.get("card").get("lastDownloadAt").isNull()).isTrue();
        assertThat(jan.get("nextDocument").get("kind").asString()).isEqualTo("DRIVER_CARD");
        assertThat(jan.get("defaultVehicle").isNull()).isTrue();
        assertThat(jan.get("user").isNull()).isTrue();

        create(c, driver("Adam", "Abacki"));
        List<String> names = names(body(c.get("/app/drivers")));
        assertThat(names).contains("Adam Abacki", "Jan Zieliński");
        assertThat(names.indexOf("Adam Abacki")).isLessThan(names.indexOf("Jan Zieliński"));

        body.put("status", "INACTIVE");
        body.put("employmentTo", "2026-12-31");
        body.remove("cardNumber");
        MvcResult updated = c.put("/app/drivers/" + id, body);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated).get("status").asString()).isEqualTo("INACTIVE");
        assertThat(body(updated).get("card").isNull()).as("an empty card number removes the card").isTrue();
        assertThat(body(updated).get("nextDocument").isNull()).isTrue();

        assertThat(c.delete("/app/drivers/" + id).getResponse().getStatus()).isEqualTo(204);
        assertThat(c.get("/app/drivers/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(names(body(c.get("/app/drivers")))).doesNotContain("Jan Zieliński");
    }

    @Test
    void cardNumberIsUniqueAndFreedByDeletion() throws Exception {
        Client c = ownerClient();
        Map<String, Object> first = driver("Piotr", "Nowak");
        first.put("cardNumber", "DE9876543210AB");
        first.put("cardIssuingCountry", "DE");
        long firstId = create(c, first);
        Map<String, Object> second = driver("Paweł", "Nowak");
        second.put("cardNumber", "de 9876543210ab");
        second.put("cardIssuingCountry", "DE");
        MvcResult taken = c.post("/app/drivers", second);
        assertThat(taken.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(taken).get("details").get("cardNumber").asString()).isEqualTo("driver.card.number_taken");

        // Saving the driver with their own card is fine; once deleted, the number is free again.
        assertThat(c.put("/app/drivers/" + firstId, first).getResponse().getStatus()).isEqualTo(200);
        assertThat(c.delete("/app/drivers/" + firstId).getResponse().getStatus()).isEqualTo(204);
        create(c, second);
    }

    @Test
    void invalidInputAnswers422PerField() throws Exception {
        Client c = ownerClient();
        Map<String, Object> bad = driver("Ewa", "Kowal");
        bad.put("email", "not-an-email");
        bad.put("country", "POL");
        bad.put("status", "RETIRED");
        bad.put("employmentFrom", "2026-05-01");
        bad.put("employmentTo", "2026-04-01");
        bad.put("cardNumber", "PL-1");
        MvcResult res = c.post("/app/drivers", bad);
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        JsonNode details = body(res).get("details");
        assertThat(details.get("email").asString()).isEqualTo("validation.invalid_format");
        assertThat(details.get("country").asString()).isEqualTo("validation.invalid_format");
        assertThat(details.get("status").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("employmentTo").asString()).isEqualTo("validation.invalid_value");
        assertThat(details.get("cardIssuingCountry").asString()).isEqualTo("validation.required");

        MvcResult noName = c.post("/app/drivers", driver(" ", "Kowal"));
        assertThat(body(noName).get("details").get("firstName").asString()).isEqualTo("validation.required");
    }

    @Test
    void documentsAndCardGiveTheNextDeadline() throws Exception {
        Client c = ownerClient();
        Map<String, Object> body = driver("Marek", "Wiśniewski");
        body.put("cardNumber", "PL55554444333322");
        body.put("cardIssuingCountry", "PL");
        body.put("cardValidTo", "2028-01-31");
        long id = create(c, body);
        String docs = "/app/drivers/" + id + "/documents";
        MvcResult license = c.post(docs, Map.of("kind", "LICENSE", "number", "00123/20/1465",
                "validFrom", "2020-02-01", "validTo", "2035-02-01"));
        assertThat(license.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(license).get("remindDays").asInt()).isEqualTo(30);
        MvcResult medical = c.post(docs, Map.of("kind", "MEDICAL", "validTo", "2027-06-30", "remindDays", 60));
        long medicalId = body(medical).get("id").asLong();
        assertThat(c.post(docs, Map.of("kind", "OTHER")).getResponse().getStatus()).isEqualTo(201);

        JsonNode list = body(c.get(docs));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("kind").asString()).isEqualTo("MEDICAL");
        assertThat(list.get(2).get("validTo").isNull()).isTrue();
        JsonNode next = body(c.get("/app/drivers/" + id)).get("nextDocument");
        assertThat(next.get("kind").asString()).isEqualTo("MEDICAL");
        assertThat(next.get("validTo").asString()).isEqualTo("2027-06-30");

        // The renewed medical lies after the card's expiry: now the card is next, in the list as well.
        c.put(docs + "/" + medicalId, Map.of("kind", "MEDICAL", "validTo", "2029-06-30"));
        JsonNode inList = null;
        for (JsonNode d : body(c.get("/app/drivers"))) {
            if (d.get("id").asLong() == id) {
                inList = d;
            }
        }
        assertThat(inList).isNotNull();
        assertThat(inList.get("nextDocument").get("kind").asString()).isEqualTo("DRIVER_CARD");
        assertThat(inList.get("nextDocument").get("validTo").asString()).isEqualTo("2028-01-31");

        MvcResult backwards = c.post(docs, Map.of("kind", "ADR", "validFrom", "2026-05-01", "validTo", "2026-04-01"));
        assertThat(body(backwards).get("details").get("validTo").asString()).isEqualTo("validation.invalid_value");
        MvcResult unknown = c.post(docs, Map.of("kind", "PILOT_LICENSE"));
        assertThat(body(unknown).get("details").get("kind").asString()).isEqualTo("validation.invalid_value");

        assertThat(c.delete(docs + "/" + medicalId).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get(docs))).hasSize(2);
        assertThat(c.delete(docs + "/" + medicalId).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void vehicleDefaultDriverLinksBothWaysAndIsClearedOnDelete() throws Exception {
        Client c = ownerClient();
        long driverId = create(c, driver("Tomasz", "Lis"));
        Map<String, Object> truck = new HashMap<>();
        truck.put("kind", "TRACTOR");
        truck.put("plate", "WPI 8800");
        truck.put("defaultDriverId", driverId);
        MvcResult vehicle = c.post("/app/fleet/vehicles", truck);
        assertThat(vehicle.getResponse().getStatus()).isEqualTo(201);
        long vehicleId = body(vehicle).get("id").asLong();
        assertThat(body(vehicle).get("defaultDriver").get("id").asLong()).isEqualTo(driverId);
        assertThat(body(vehicle).get("defaultDriver").get("name").asString()).isEqualTo("Tomasz Lis");

        JsonNode tomasz = body(c.get("/app/drivers/" + driverId));
        assertThat(tomasz.get("defaultVehicle").get("id").asLong()).isEqualTo(vehicleId);
        assertThat(tomasz.get("defaultVehicle").get("plate").asString()).isEqualTo("WPI 8800");

        assertThat(c.delete("/app/drivers/" + driverId).getResponse().getStatus()).isEqualTo(204);
        assertThat(body(c.get("/app/fleet/vehicles/" + vehicleId)).get("defaultDriver").isNull()).isTrue();
        MvcResult deletedDriver = c.put("/app/fleet/vehicles/" + vehicleId, truck);
        assertThat(body(deletedDriver).get("details").get("defaultDriverId").asString())
                .isEqualTo("validation.invalid_value");
    }

    @Test
    void privilegesDecideWhoMaySeeAndChange() throws Exception {
        Firma firma = mainFirma();
        long id = create(ownerClient(), driver("Olga", "Mazur"));
        AppUser accountant = createUser("accountant-drivers@test.local", "Acct-Pass-123", Role.ACCOUNTANT,
                firma.getId(), true);
        Client viewer = client();
        viewer.login(accountant.getLogin(), "Acct-Pass-123");
        assertThat(viewer.get("/app/drivers").getResponse().getStatus()).isEqualTo(200);
        assertThat(viewer.get("/app/drivers/" + id + "/documents").getResponse().getStatus()).isEqualTo(200);
        assertThat(viewer.post("/app/drivers", driver("X", "Y")).getResponse().getStatus()).isEqualTo(403);
        assertThat(viewer.delete("/app/drivers/" + id).getResponse().getStatus()).isEqualTo(403);
        assertThat(viewer.post("/app/drivers/" + id + "/documents", Map.of("kind", "OTHER"))
                .getResponse().getStatus()).isEqualTo(403);

        AppUser driverUser = createUser("driver-drivers@test.local", "Driver-Pass-2", Role.DRIVER, firma.getId(), true);
        Client app = client();
        app.login(driverUser.getLogin(), "Driver-Pass-2");
        assertThat(app.get("/app/drivers").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void driversOfAnotherFirmaAreInvisible() throws Exception {
        Firma other = new Firma();
        other.setName("Other Drivers s.r.o.");
        other.setShortCode("DRVX");
        other.setCountry("CZ");
        other = firmas.save(other);
        AppUser otherOwner = createUser("owner-drvx@test.local", "OtherOwner-2", Role.OWNER, other.getId(), true);
        Client foreign = client();
        foreign.login(otherOwner.getLogin(), "OtherOwner-2");
        Map<String, Object> body = driver("Karel", "Novák");
        body.put("cardNumber", "CZ1111222233334444");
        body.put("cardIssuingCountry", "CZ");
        long foreignId = create(foreign, body);

        Client c = ownerClient();
        // The same card number is fine in another firma.
        Map<String, Object> own = driver("Karol", "Nowak");
        own.put("cardNumber", "CZ1111222233334444");
        own.put("cardIssuingCountry", "CZ");
        create(c, own);
        assertThat(names(body(c.get("/app/drivers")))).doesNotContain("Karel Novák");
        assertThat(c.get("/app/drivers/" + foreignId).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.put("/app/drivers/" + foreignId, driver("A", "B")).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.delete("/app/drivers/" + foreignId).getResponse().getStatus()).isEqualTo(404);
        assertThat(c.get("/app/drivers/" + foreignId + "/documents").getResponse().getStatus()).isEqualTo(404);
        Map<String, Object> truck = new HashMap<>();
        truck.put("kind", "TRUCK");
        truck.put("plate", "WPI 8801");
        truck.put("defaultDriverId", foreignId);
        assertThat(body(c.post("/app/fleet/vehicles", truck)).get("details").get("defaultDriverId").asString())
                .isEqualTo("validation.invalid_value");
    }
}
