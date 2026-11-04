package com.rutera.core.push;

import com.rutera.common.i18n.LangService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web Push of the web application and the driver cabinet (see {@link PushService}).
 * <pre>
 * GET    /app/push/key                                   -> {publicKey} | 404 push.disabled
 * POST   /app/push/subscriptions {endpoint, keys: {p256dh, auth}} -> 204 (upsert by endpoint, current user)
 * DELETE /app/push/subscriptions {endpoint}              -> 204
 * POST   /app/push/test                                  -> {sent, failed}
 * </pre>
 */
@RestController
public class PushController {

    private final PushService push;
    private final LangService lang;

    public PushController(PushService push, LangService lang) {
        this.push = push;
        this.lang = lang;
    }

    @GetMapping("/app/push/key")
    public Map<String, String> key() {
        return Map.of("publicKey", push.publicKey());
    }

    @PostMapping("/app/push/subscriptions")
    public ResponseEntity<Void> subscribe(@RequestBody PushService.SubscriptionInput input,
                                          HttpServletRequest request) {
        push.subscribe(input, request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().build();
    }

    public record EndpointInput(String endpoint) {
    }

    @DeleteMapping("/app/push/subscriptions")
    public ResponseEntity<Void> unsubscribe(@RequestBody(required = false) EndpointInput input) {
        push.unsubscribe(input == null ? null : input.endpoint());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/app/push/test")
    public PushService.TestResult test() {
        return push.test(code -> new PushService.Payload("test", lang.get("notify.test.title", code),
                lang.get("notify.test.body", code), PushNotices.DRIVER_URL, "test"));
    }
}
