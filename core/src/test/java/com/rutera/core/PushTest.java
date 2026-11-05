package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.DriverDocument;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.PushSubscription;
import com.rutera.common.domain.VehicleDocument;
import com.rutera.common.repository.DriverDocumentRepository;
import com.rutera.common.repository.PushSubscriptionRepository;
import com.rutera.common.repository.VehicleDocumentRepository;
import com.rutera.common.security.Role;
import com.rutera.core.push.DeadlineNotices;
import com.rutera.core.push.TestPushDevice;
import com.rutera.core.push.WebPush;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web Push: the key, subscriptions of a device, and the notifications of the driver cabinet as a stub push service
 * receives them (VAPID header, aes128gcm body that the device decrypts), including the removal of gone devices.
 */
class PushTest extends AbstractIntegrationTest {

    private record Received(String path, Map<String, String> headers, byte[] body) {
    }

    private static final BlockingQueue<Received> RECEIVED = new LinkedBlockingQueue<>();
    private static final HttpServer STUB;
    private static final String[] KEYS;

    static {
        try {
            KEYS = WebPush.generateKeys();
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/push/", ex -> {
                Map<String, String> headers = new HashMap<>();
                ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
                RECEIVED.add(new Received(ex.getRequestURI().getPath(), headers, ex.getRequestBody().readAllBytes()));
                ex.sendResponseHeaders(ex.getRequestURI().getPath().contains("/gone/") ? 410 : 201, -1);
                ex.close();
            });
            STUB.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void push(DynamicPropertyRegistry registry) {
        registry.add("rutera.push.vapid-public", () -> KEYS[0]);
        registry.add("rutera.push.vapid-private", () -> KEYS[1]);
        registry.add("rutera.push.subject", () -> "mailto:ops@test.local");
    }

    @Autowired
    private PushSubscriptionRepository subscriptions;
    @Autowired
    private DeadlineNotices deadlines;
    @Autowired
    private DriverDocumentRepository driverDocuments;
    @Autowired
    private VehicleDocumentRepository vehicleDocuments;

    /** The user's devices, read in root scope (the test thread has no firma). */
    private List<PushSubscription> devices(Long userId) throws Exception {
        return RequestContext.runAsRoot(null, () -> subscriptions.findByUserIdOrderByIdAsc(userId));
    }

    private static String endpoint(String name) {
        return "http://127.0.0.1:" + STUB.getAddress().getPort() + "/push/" + name;
    }

    private static Map<String, Object> subscription(String endpoint, TestPushDevice device) {
        return Map.of("endpoint", endpoint, "keys", Map.of("p256dh", device.p256dh(), "auth", device.auth()));
    }

    private JsonNode next(TestPushDevice device, String path) throws Exception {
        Received r = RECEIVED.poll(10, TimeUnit.SECONDS);
        assertThat(r).as("a notification arrived").isNotNull();
        assertThat(r.path()).endsWith(path);
        assertThat(r.headers().get("authorization")).startsWith("vapid t=").contains(", k=" + KEYS[0]);
        assertThat(r.headers().get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(r.headers().get("ttl")).isEqualTo("86400");
        return json.readTree(device.decrypt(r.body()));
    }

    @Test
    void theDriverGetsNotifiedOfTripsAndAbsenceDecisions() throws Exception {
        RECEIVED.clear();
        Firma firma = new Firma();
        firma.setName("Push Test");
        firma.setShortCode("PUSH1");
        firma.setCountry("PL");
        firma.setTimezone("Europe/Warsaw");
        firma = firmas.save(firma);
        AppUser owner = createUser("owner-push@test.local", "PushOwner-1", Role.OWNER, firma.getId(), true);
        AppUser login = createUser("driver-push@test.local", "PushDriver-1", Role.DRIVER, firma.getId(), true);
        login.setLang("pl");
        users.save(login);
        Client office = client();
        assertThat(office.login(owner.getLogin(), "PushOwner-1").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> d = new HashMap<>();
        d.put("firstName", "Jan");
        d.put("lastName", "Kierowca");
        d.put("userId", login.getId());
        long driverId = body(office.post("/app/drivers", d)).get("id").asLong();
        long vehicleId = body(office.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "PU 0001")))
                .get("id").asLong();

        Client phone = client();
        assertThat(phone.login(login.getLogin(), "PushDriver-1").getResponse().getStatus()).isEqualTo(200);
        assertThat(body(phone.get("/app/push/key")).get("publicKey").asString()).isEqualTo(KEYS[0]);
        TestPushDevice device = new TestPushDevice();
        Map<String, Object> bad = Map.of("endpoint", "ftp://x", "keys", Map.of("p256dh", "AAAA", "auth", device.auth()));
        JsonNode invalid = body(phone.post("/app/push/subscriptions", bad));
        assertThat(invalid.get("details").get("endpoint").asString()).isEqualTo("validation.invalid_value");
        assertThat(invalid.get("details").get("keys.p256dh").asString()).isEqualTo("validation.invalid_value");
        assertThat(phone.post("/app/push/subscriptions", subscription(endpoint("phone"), device)).getResponse()
                .getStatus()).isEqualTo(204);
        assertThat(phone.post("/app/push/subscriptions", subscription(endpoint("phone"), device)).getResponse()
                .getStatus()).as("upsert").isEqualTo(204);
        assertThat(devices(login.getId())).hasSize(1);

        Map<String, Object> trip = new HashMap<>(Map.of("vehicleId", vehicleId, "mainDriverId", driverId,
                "plannedStart", "2026-12-01T05:00:00Z", "orderIds", List.of(), "stops", List.of()));
        JsonNode created = body(office.post("/app/trips", trip));
        long tripId = created.get("id").asLong();
        JsonNode assigned = next(device, "/push/phone");
        assertThat(assigned.get("type").asString()).isEqualTo("trip.assigned");
        assertThat(assigned.get("title").asString()).isEqualTo("Nowa trasa " + created.get("number").asString());
        assertThat(assigned.get("body").asString()).as("Warsaw time").startsWith("Planowany start:").contains("06:00");
        assertThat(assigned.get("url").asString()).isEqualTo("/driver");
        assertThat(assigned.get("tag").asString()).isEqualTo("trip-" + tripId);

        trip.put("status", "CANCELLED");
        assertThat(office.put("/app/trips/" + tripId, trip).getResponse().getStatus()).isEqualTo(200);
        assertThat(next(device, "/push/phone").get("type").asString()).isEqualTo("trip.cancelled");

        long absenceId = body(phone.post("/app/drivers/" + driverId + "/absences", Map.of("kind", "VACATION",
                "fromDate", "2026-12-21", "toDate", "2026-12-23"))).get("id").asLong();
        assertThat(RECEIVED.poll(500, TimeUnit.MILLISECONDS)).as("the driver's own request: no push").isNull();
        office.patch("/app/drivers/" + driverId + "/absences/" + absenceId, Map.of("status", "APPROVED",
                "note", "Udanych świąt"));
        JsonNode approved = next(device, "/push/phone");
        assertThat(approved.get("type").asString()).isEqualTo("absence.approved");
        assertThat(approved.get("title").asString()).isEqualTo("Nieobecność zatwierdzona");
        assertThat(approved.get("body").asString()).startsWith("Urlop: ").endsWith("Udanych świąt");
        assertThat(approved.get("url").asString()).isEqualTo("/driver/me");

        JsonNode test = body(phone.post("/app/push/test", null));
        assertThat(test.get("sent").asInt()).isEqualTo(1);
        JsonNode testPayload = next(device, "/push/phone");
        assertThat(testPayload.get("type").asString()).isEqualTo("test");
        assertThat(testPayload.get("url").asString()).as("the start screen of the role").isEqualTo("/");

        TestPushDevice old = new TestPushDevice();
        phone.post("/app/push/subscriptions", subscription(endpoint("gone/tablet"), old));
        JsonNode mixed = body(phone.post("/app/push/test", null));
        assertThat(mixed.get("sent").asInt()).isEqualTo(1);
        assertThat(mixed.get("failed").asInt()).isEqualTo(1);
        RECEIVED.clear();
        assertThat(devices(login.getId())).as("410 removes the device").hasSize(1);

        assertThat(phone.delete("/app/push/subscriptions").getResponse().getStatus()).isEqualTo(204);
        assertThat(phone.delete("/app/push/subscriptions", Map.of("endpoint", endpoint("phone"))).getResponse()
                .getStatus()).isEqualTo(204);
        assertThat(devices(login.getId())).isEmpty();
        assertThat(body(phone.post("/app/push/test", null)).get("sent").asInt()).isZero();
    }

    private Client signedIn(AppUser user, String password) throws Exception {
        Client c = client();
        assertThat(c.login(user.getLogin(), password).getResponse().getStatus()).isEqualTo(200);
        return c;
    }

    /** What these many notifications carry, by the name of the device that got them. */
    private Map<String, JsonNode> arrived(Map<String, TestPushDevice> devices, int count) throws Exception {
        Map<String, JsonNode> byDevice = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Received r = RECEIVED.poll(10, TimeUnit.SECONDS);
            assertThat(r).as("notification %d of %d arrived", i + 1, count).isNotNull();
            String name = r.path().substring("/push/".length());
            byDevice.put(name, json.readTree(devices.get(name).decrypt(r.body())));
        }
        return byDevice;
    }

    private DriverDocument driverDocument(long firmaId, long driverId, DriverDocument.Kind kind, LocalDate validTo,
                                          int remindDays) throws Exception {
        DriverDocument doc = new DriverDocument();
        doc.setFirmaId(firmaId);
        doc.setDriverId(driverId);
        doc.setKind(kind);
        doc.setValidTo(validTo);
        doc.setRemindDays(remindDays);
        return RequestContext.runAsFirma(firmaId, null, () -> driverDocuments.save(doc));
    }

    @Test
    void remindersOfExpiringDocumentsGoOutOncePerLeadTime() throws Exception {
        RECEIVED.clear();
        // 10:30 in Warsaw, the first day of the story.
        Instant now = Instant.parse("2031-03-03T09:30:00Z");
        LocalDate today = LocalDate.parse("2031-03-03");
        Firma firma = new Firma();
        firma.setName("Deadline Test");
        firma.setShortCode("PUSH2");
        firma.setCountry("PL");
        firma.setTimezone("Europe/Warsaw");
        long firmaId = firmas.save(firma).getId();
        AppUser owner = createUser("owner-deadline@test.local", "DeadOwner-1", Role.OWNER, firmaId, true);
        AppUser dispatcher = createUser("disp-deadline@test.local", "DeadDisp-1", Role.DISPATCHER, firmaId, true);
        AppUser accountant = createUser("acc-deadline@test.local", "DeadAcc-1", Role.ACCOUNTANT, firmaId, true);
        AppUser login = createUser("driver-deadline@test.local", "DeadDriver-1", Role.DRIVER, firmaId, true);
        login.setLang("pl");
        users.save(login);
        Client office = signedIn(owner, "DeadOwner-1");
        Map<String, Object> d = new HashMap<>();
        d.put("firstName", "Anna");
        d.put("lastName", "Terminowa");
        d.put("userId", login.getId());
        d.put("cardNumber", "PL99990000111122");
        d.put("cardIssuingCountry", "PL");
        d.put("cardValidTo", today.plusDays(1).toString());
        long driverId = body(office.post("/app/drivers", d)).get("id").asLong();
        long vehicleId = body(office.post("/app/fleet/vehicles", Map.of("kind", "TRACTOR", "plate", "DL 0001")))
                .get("id").asLong();
        DriverDocument license = driverDocument(firmaId, driverId, DriverDocument.Kind.LICENSE, today.plusDays(10), 30);
        assertThat(deadlines.run(now)).as("no device yet: nothing is sent or recorded").isZero();

        Map<String, TestPushDevice> devices = new HashMap<>();
        for (String name : List.of("driver", "owner", "dispatcher", "accountant")) {
            devices.put(name, new TestPushDevice());
        }
        assertThat(signedIn(login, "DeadDriver-1").post("/app/push/subscriptions",
                subscription(endpoint("driver"), devices.get("driver"))).getResponse().getStatus()).isEqualTo(204);
        office.post("/app/push/subscriptions", subscription(endpoint("owner"), devices.get("owner")));
        signedIn(dispatcher, "DeadDisp-1").post("/app/push/subscriptions",
                subscription(endpoint("dispatcher"), devices.get("dispatcher")));
        signedIn(accountant, "DeadAcc-1").post("/app/push/subscriptions",
                subscription(endpoint("accountant"), devices.get("accountant")));

        assertThat(deadlines.run(now.minus(8, ChronoUnit.HOURS))).as("02:30 in Warsaw: not at night").isZero();
        assertThat(deadlines.run(now)).as("the license and the card").isEqualTo(2);
        Received first = RECEIVED.poll(10, TimeUnit.SECONDS);
        Received second = RECEIVED.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        JsonNode document = json.readTree(devices.get("driver").decrypt(first.body()));
        JsonNode card = json.readTree(devices.get("driver").decrypt(second.body()));
        assertThat(document.get("type").asString()).isEqualTo("deadline.document");
        assertThat(document.get("title").asString()).isEqualTo("Twój dokument wkrótce wygasa");
        assertThat(document.get("body").asString()).startsWith("Prawo jazdy: ważność do ").contains("2031");
        assertThat(document.get("url").asString()).isEqualTo("/driver/documents");
        assertThat(document.get("tag").asString()).isEqualTo("deadline-document-" + license.getId());
        assertThat(card.get("type").asString()).isEqualTo("deadline.card");
        assertThat(card.get("title").asString()).isEqualTo("Twoja karta kierowcy wkrótce wygasa");
        assertThat(card.get("body").asString()).startsWith("Karta kierowcy: ważność do ");
        assertThat(card.get("url").asString()).isEqualTo("/driver/documents");
        assertThat(card.get("tag").asString()).startsWith("deadline-card-");

        assertThat(deadlines.run(now)).as("no repeat").isZero();
        assertThat(deadlines.run(now.plus(1, ChronoUnit.HOURS))).as("no repeat in the same lead time").isZero();

        // Four days on: 6 days left is the next lead time of the license; the card has expired.
        Instant later = now.plus(4, ChronoUnit.DAYS);
        LocalDate laterDay = today.plusDays(4);
        assertThat(deadlines.run(later)).isEqualTo(1);
        assertThat(arrived(devices, 1).get("driver").get("tag").asString())
                .isEqualTo("deadline-document-" + license.getId());
        assertThat(deadlines.run(later)).isZero();

        // A renewed date starts again, though it stays near.
        license.setValidTo(laterDay.plusDays(5));
        RequestContext.runAsFirma(firmaId, null, () -> driverDocuments.save(license));
        assertThat(deadlines.run(later)).isEqualTo(1);
        arrived(devices, 1);

        // A vehicle document goes to the owner and the dispatcher, in their language, not to the others.
        VehicleDocument insurance = new VehicleDocument();
        insurance.setFirmaId(firmaId);
        insurance.setVehicleId(vehicleId);
        insurance.setKind(VehicleDocument.Kind.INSURANCE_OC);
        insurance.setValidTo(laterDay.plusDays(30));
        long insuranceId = RequestContext.runAsFirma(firmaId, null, () -> vehicleDocuments.save(insurance)).getId();
        assertThat(deadlines.run(later)).isEqualTo(1);
        Map<String, JsonNode> officeGot = arrived(devices, 2);
        assertThat(officeGot.keySet()).containsExactlyInAnyOrder("owner", "dispatcher");
        JsonNode vehicle = officeGot.get("owner");
        assertThat(vehicle.get("type").asString()).isEqualTo("deadline.vehicle_document");
        assertThat(vehicle.get("title").asString()).isEqualTo("Vehicle document expiring: DL 0001");
        assertThat(vehicle.get("body").asString()).contains(": valid until ");
        assertThat(vehicle.get("url").asString()).isEqualTo("/fleet/" + vehicleId);
        assertThat(vehicle.get("tag").asString()).isEqualTo("deadline-vehicle-" + insuranceId);

        // The document's own remind_days: 0 is off, 5 waits for 5 days left.
        driverDocument(firmaId, driverId, DriverDocument.Kind.MEDICAL, laterDay.plusDays(3), 0);
        DriverDocument adr = driverDocument(firmaId, driverId, DriverDocument.Kind.ADR, laterDay.plusDays(6), 5);
        assertThat(deadlines.run(later)).isZero();
        Instant dayAfter = later.plus(1, ChronoUnit.DAYS);
        assertThat(deadlines.run(dayAfter)).isEqualTo(1);
        assertThat(arrived(devices, 1).get("driver").get("tag").asString())
                .isEqualTo("deadline-document-" + adr.getId());

        // A passport replaced by a newer record stays silent; documents of the kind OTHER do not replace each other.
        driverDocument(firmaId, driverId, DriverDocument.Kind.PASSPORT, laterDay.plusDays(3), 30);
        driverDocument(firmaId, driverId, DriverDocument.Kind.PASSPORT, laterDay.plusDays(400), 30);
        assertThat(deadlines.run(dayAfter)).isZero();
        driverDocument(firmaId, driverId, DriverDocument.Kind.OTHER, laterDay.plusDays(3), 30);
        driverDocument(firmaId, driverId, DriverDocument.Kind.OTHER, laterDay.plusDays(400), 30);
        assertThat(deadlines.run(dayAfter)).isEqualTo(1);
        arrived(devices, 1);
        assertThat(RECEIVED.poll(300, TimeUnit.MILLISECONDS)).as("nothing more").isNull();
    }
}
