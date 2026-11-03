package com.rutera.core.auth;

import com.rutera.core.security.RuteraSecurityProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed sign-ins per client address (in memory: one application instance). After
 * {@code rutera.security.ip-max-failures} failures within {@code ip-window} the address gets 429
 * {@code auth.login.throttled} until the window ends, whatever login it tries; the per-account lockout
 * ({@link LoginAttemptService}) works on its own. A success does not reset the window, so an attacker cannot clear it
 * with an account of their own. Behind a reverse proxy the address is the client's only with
 * {@code server.forward-headers-strategy} (RUTERA_FORWARD_HEADERS=native).
 */
@Component
public class LoginThrottle {

    private record Window(long start, int failures) {
    }

    private final int maxFailures;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginThrottle(RuteraSecurityProperties properties) {
        this.maxFailures = properties.ipMaxFailures();
        this.windowMillis = properties.ipWindow().toMillis();
    }

    /** Seconds the address has to wait, or 0 when it may try. */
    public long retryAfter(String address, long nowMillis) {
        Window w = windows.get(key(address));
        if (w == null || nowMillis - w.start() >= windowMillis || w.failures() < maxFailures) {
            return 0;
        }
        return Math.max(1, (w.start() + windowMillis - nowMillis + 999) / 1000);
    }

    /** Counts a failure; true when this failure reached the limit (the moment to write the audit entry). */
    public boolean failed(String address, long nowMillis) {
        Window w = windows.compute(key(address), (k, old) -> old == null || nowMillis - old.start() >= windowMillis
                ? new Window(nowMillis, 1) : new Window(old.start(), old.failures() + 1));
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> nowMillis - e.getValue().start() >= windowMillis);
        }
        return w.failures() == maxFailures;
    }

    private static String key(String address) {
        return address == null ? "unknown" : address;
    }
}
