package com.rutera.core.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls per token and minute (fixed window, in memory: one application instance). {@code rutera.api.rate-per-minute}
 * sets the limit; 0 switches it off.
 */
@Component
public class ApiRateLimiter {

    private record Window(long minute, int count) {
    }

    private final int perMinute;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimiter(@Value("${rutera.api.rate-per-minute:120}") int perMinute) {
        this.perMinute = perMinute;
    }

    /** Seconds to wait when the token is over its limit at {@code nowMillis}, else 0 (and the call is counted). */
    public long acquire(Long tokenId, long nowMillis) {
        if (perMinute <= 0) {
            return 0;
        }
        long minute = nowMillis / 60_000;
        Window w = windows.compute(tokenId, (id, old) -> old == null || old.minute() != minute
                ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        if (w.count() <= perMinute) {
            return 0;
        }
        return Math.max(1, ((minute + 1) * 60_000 - nowMillis + 999) / 1000);
    }
}
