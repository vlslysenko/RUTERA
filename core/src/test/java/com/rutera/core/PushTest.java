package com.rutera.core;

import com.rutera.common.domain.AppUser;
import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.PushSubscription;
import com.rutera.common.repository.PushSubscriptionRepository;
import com.rutera.common.security.Role;
import com.rutera.core.push.TestPushDevice;
import com.rutera.core.push.WebPush;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
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
        assertThat(next(device, "/push/phone").get("type").asString()).isEqualTo("test");

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
}
