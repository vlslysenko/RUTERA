package com.rutera.core.push;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.PushSubscription;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.PushSubscriptionRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.api.ApiTokenService;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Web Push to the devices of users ({@code /app/push}): subscriptions per device and the delivery of notifications.
 * Switched on by the VAPID keys {@code rutera.push.vapid-public} / {@code vapid-private} (RUTERA_PUSH_VAPID_*);
 * without them every endpoint answers 404 {@code push.disabled} and nothing is sent. Notifications are encrypted per
 * device (RFC 8291) and sent after the commit of the business transaction by one background thread, so a slow or
 * failing push service never delays or fails a request. 404 / 410 from the push service remove the subscription,
 * so do 10 failures in a row.
 */
@Service
public class PushService {

    static final int MAX_PER_USER = 10;
    static final int MAX_FAILURES = 10;
    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushSubscriptionRepository subscriptions;
    private final AppUserRepository users;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    /** Reads for a notification in their own transaction: a failure there must not roll back the business one. */
    private final TransactionTemplate separate;
    private final WebPush.Vapid vapid;
    private final boolean allowHttp;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1000), r -> {
                Thread t = new Thread(r, "push-sender");
                t.setDaemon(true);
                return t;
            });

    public PushService(PushSubscriptionRepository subscriptions, AppUserRepository users, ObjectMapper json,
                       PlatformTransactionManager transactionManager,
                       @Value("${rutera.push.vapid-public:}") String publicKey,
                       @Value("${rutera.push.vapid-private:}") String privateKey,
                       @Value("${rutera.push.subject:}") String subject,
                       @Value("${rutera.public-url:}") String publicUrl,
                       @Value("${rutera.push.allow-http:false}") boolean allowHttp) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.separate.setReadOnly(true);
        this.allowHttp = allowHttp;
        WebPush.Vapid keys = null;
        if (!publicKey.isBlank() && !privateKey.isBlank()) {
            String sub = !subject.isBlank() ? subject.strip()
                    : !publicUrl.isBlank() ? publicUrl.strip() : "mailto:push@rutera.invalid";
            try {
                keys = WebPush.vapid(publicKey, privateKey, sub);
                log.info("Web Push is on (VAPID subject {})", sub);
            } catch (GeneralSecurityException e) {
                log.error("Web Push is off: the VAPID keys cannot be used ({})", e.getMessage());
            }
        }
        this.vapid = keys;
    }

    @PreDestroy
    void stop() {
        sender.shutdownNow();
    }

    /** One notification for one device: what the service worker gets from {@code event.data.json()}. */
    public record Payload(String type, String title, String body, String url, String tag) {
    }

    public record SubscriptionInput(String endpoint, Keys keys) {
    }

    public record Keys(String p256dh, String auth) {
    }

    public record TestResult(int sent, int failed) {
    }

    private record Delivery(Long subscriptionId, String endpoint, String p256dh, String auth, String topic,
                            byte[] payload) {
    }

    public boolean enabled() {
        return vapid != null;
    }

    // --- the device's subscription -------------------------------------------------------------------------

    public String publicKey() {
        requireEnabled();
        return vapid.publicKeyText();
    }

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    public void subscribe(SubscriptionInput in, String userAgent) {
        requireEnabled();
        AuthenticatedUser me = requireFirmaUser();
        Map<String, String> errors = new LinkedHashMap<>();
        String endpoint = in == null || in.endpoint() == null ? null : in.endpoint().strip();
        if (endpoint == null || endpoint.isEmpty()) {
            errors.put("endpoint", ErrorCodes.FIELD_REQUIRED);
        } else if (!validEndpoint(endpoint)) {
            errors.put("endpoint", ErrorCodes.FIELD_INVALID_VALUE);
        }
        Keys keys = in == null ? null : in.keys();
        String p256dh = keys == null ? null : keys.p256dh();
        String auth = keys == null ? null : keys.auth();
        checkKey("keys.p256dh", p256dh, 65, errors);
        checkKey("keys.auth", auth, 16, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String hash = ApiTokenService.hash(endpoint);
        String agent = userAgent == null ? null : userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
        root(() -> tx.execute(s -> {
            PushSubscription sub = subscriptions.findByEndpointHash(hash).orElseGet(PushSubscription::new);
            sub.setFirmaId(me.firmaId());
            sub.setUserId(me.id());
            sub.setEndpoint(endpoint);
            sub.setEndpointHash(hash);
            sub.setP256dh(p256dh.strip());
            sub.setAuth(auth.strip());
            sub.setUserAgent(agent);
            sub.setFailures(0);
            subscriptions.save(sub);
            List<PushSubscription> mine = subscriptions.findByUserIdOrderByIdAsc(me.id());
            for (int i = 0; i < mine.size() - MAX_PER_USER; i++) {
                subscriptions.delete(mine.get(i));
            }
            return null;
        }));
    }

    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    public void unsubscribe(String endpoint) {
        requireEnabled();
        AuthenticatedUser me = CurrentUser.require();
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        String hash = ApiTokenService.hash(endpoint.strip());
        root(() -> tx.execute(s -> {
            subscriptions.findByEndpointHash(hash).filter(x -> x.getUserId().equals(me.id()))
                    .ifPresent(subscriptions::delete);
            return null;
        }));
    }

    /** A test notification to the caller's own devices, sent at once. */
    @RequiresPrivilege(Privileges.PROFILE_SELF_MANAGE)
    public TestResult test(Function<String, Payload> texts) {
        requireEnabled();
        AuthenticatedUser me = requireFirmaUser();
        List<Delivery> deliveries = root(() -> tx.execute(s -> deliveries(List.of(me.id()), texts)));
        int sent = 0;
        for (Delivery d : deliveries) {
            if (deliver(d)) {
                sent++;
            }
        }
        return new TestResult(sent, deliveries.size() - sent);
    }

    // --- notifications -------------------------------------------------------------------------------------

    /**
     * Notifies the devices of these users after the current transaction commits (at once without one).
     * {@code texts} builds the payload in a user's language (its argument is the language code).
     */
    public void notify(Collection<Long> userIds, Function<String, Payload> texts) {
        if (vapid == null || userIds.isEmpty()) {
            return;
        }
        List<Delivery> deliveries;
        try {
            deliveries = root(() -> separate.execute(s -> deliveries(userIds, texts)));
        } catch (RuntimeException e) {
            log.warn("Push notification not prepared: {}", e.toString());
            return;
        }
        if (deliveries.isEmpty()) {
            return;
        }
        Runnable send = () -> deliveries.forEach(this::deliver);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(send);
                }
            });
        } else {
            submit(send);
        }
    }

    private void submit(Runnable send) {
        try {
            sender.execute(send);
        } catch (RejectedExecutionException e) {
            log.warn("Push queue full: notification dropped");
        }
    }

    private List<Delivery> deliveries(Collection<Long> userIds, Function<String, Payload> texts) {
        List<PushSubscription> subs = subscriptions.findByUserIdIn(userIds);
        if (subs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> langOf = new HashMap<>();
        for (AppUser u : users.findAllById(subs.stream().map(PushSubscription::getUserId).distinct().toList())) {
            langOf.put(u.getId(), u.getLang());
        }
        Map<String, byte[]> payloadOf = new HashMap<>();
        List<Delivery> result = new ArrayList<>();
        for (PushSubscription s : subs) {
            String lang = langOf.getOrDefault(s.getUserId(), "en");
            Payload p = texts.apply(lang);
            byte[] bytes = payloadOf.computeIfAbsent(lang, l -> json.writeValueAsString(p)
                    .getBytes(StandardCharsets.UTF_8));
            result.add(new Delivery(s.getId(), s.getEndpoint(), s.getP256dh(), s.getAuth(), topic(p.tag()), bytes));
        }
        return result;
    }

    /** Sends one notification; true when the push service accepted it. */
    private boolean deliver(Delivery d) {
        int status;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(d.endpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("TTL", "86400")
                    .header("Urgency", "normal")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", WebPush.authorization(vapid, d.endpoint(),
                            Instant.now().getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(WebPush.encrypt(d.payload(), d.p256dh(), d.auth())));
            if (d.topic() != null) {
                request.header("Topic", d.topic());
            }
            status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.info("Push to subscription {} failed: {}", d.subscriptionId(), e.toString());
            status = -1;
        }
        int code = status;
        try {
            root(() -> tx.execute(s -> {
                subscriptions.findById(d.subscriptionId()).ifPresent(sub -> {
                    if (code >= 200 && code < 300) {
                        sub.setLastSuccessAt(Instant.now());
                        sub.setFailures(0);
                        subscriptions.save(sub);
                    } else if (code == 404 || code == 410 || sub.getFailures() + 1 >= MAX_FAILURES) {
                        subscriptions.delete(sub);
                    } else {
                        sub.setFailures(sub.getFailures() + 1);
                        subscriptions.save(sub);
                    }
                });
                return null;
            }));
        } catch (RuntimeException e) {
            log.warn("Push subscription {} not updated: {}", d.subscriptionId(), e.toString());
        }
        return code >= 200 && code < 300;
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void requireEnabled() {
        if (vapid == null) {
            throw ApiException.notFound(ErrorCodes.PUSH_DISABLED);
        }
    }

    private static AuthenticatedUser requireFirmaUser() {
        AuthenticatedUser me = CurrentUser.require();
        if (me.firmaId() == null) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
        return me;
    }

    private boolean validEndpoint(String endpoint) {
        if (endpoint.length() > 1000) {
            return false;
        }
        try {
            URI u = new URI(endpoint);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return u.getHost() != null && ("https".equals(scheme) || (allowHttp && "http".equals(scheme)));
        } catch (Exception e) {
            return false;
        }
    }

    private static void checkKey(String field, String value, int length, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put(field, ErrorCodes.FIELD_REQUIRED);
            return;
        }
        try {
            byte[] raw = WebPush.decode(value);
            if (raw.length != length || (length == 65 && raw[0] != 0x04) || value.length() > 128) {
                errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
            }
        } catch (GeneralSecurityException e) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    /** The Topic header (RFC 8030): up to 32 base64url characters; replaces an undelivered one with the same tag. */
    private static String topic(String tag) {
        if (tag == null) {
            return null;
        }
        String t = tag.replaceAll("[^A-Za-z0-9_-]", "_");
        return t.isEmpty() ? null : t.length() > 32 ? t.substring(0, 32) : t;
    }

    private static <T> T root(Callable<T> work) {
        try {
            return RequestContext.runAsRoot(null, work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
