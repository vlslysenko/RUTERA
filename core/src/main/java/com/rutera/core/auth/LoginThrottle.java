package com.rutera.core.auth;

import com.rutera.core.security.RuteraSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Failed sign-ins per client address (in memory: one application instance). When an address reaches
 * {@code rutera.security.ip-max-failures} failures within {@code ip-window}, it is blocked for one whole window
 * from that moment: 429 {@code auth.login.throttled} whatever login it tries. The per-account lockout
 * ({@link LoginAttemptService}) works on its own. A success does not reset the count, so an attacker cannot clear it
 * with an account of their own. IPv6 addresses count per /64 network (one host may own all of it).
 * <p>
 * Behind a reverse proxy the address is the client's only with {@code server.forward-headers-strategy}
 * (RUTERA_FORWARD_HEADERS=native); otherwise all clients share the proxy's address and count together, which is
 * logged once as a warning when a request carries X-Forwarded-For. The table is bounded: expired entries are dropped
 * when it grows, and above {@link #MAX_ENTRIES} only blocked addresses are kept.
 */
@Component
public class LoginThrottle {

    static final int MAX_ENTRIES = 100_000;
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.%]*");

    /** Failures since {@code start}; {@code blockedUntil} > now while the address is blocked. */
    private record Entry(long start, int failures, long blockedUntil) {
    }

    private final int maxFailures;
    private final long windowMillis;
    private final boolean forwardHeaders;
    private final AtomicBoolean proxyWarned = new AtomicBoolean();
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LoginThrottle(RuteraSecurityProperties properties,
                         @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy) {
        this.maxFailures = properties.ipMaxFailures();
        this.windowMillis = properties.ipWindow().toMillis();
        this.forwardHeaders = !"none".equalsIgnoreCase(forwardHeadersStrategy.strip());
    }

    /** Seconds the address has to wait, or 0 when it may try. */
    public long retryAfter(String address, long nowMillis) {
        Entry e = entries.get(key(address));
        if (e == null || e.blockedUntil() <= nowMillis) {
            return 0;
        }
        return Math.max(1, (e.blockedUntil() - nowMillis + 999) / 1000);
    }

    /** Counts a failure; true when this failure blocked the address (the moment to write the audit entry). */
    public boolean failed(String address, long nowMillis) {
        Entry e = entries.compute(key(address), (k, old) -> {
            if (old == null || (nowMillis - old.start() >= windowMillis && old.blockedUntil() <= nowMillis)) {
                old = new Entry(nowMillis, 0, 0);
            }
            int failures = old.failures() + 1;
            return failures >= maxFailures
                    ? new Entry(nowMillis, 0, nowMillis + windowMillis)
                    : new Entry(old.start(), failures, old.blockedUntil());
        });
        if (entries.size() > MAX_ENTRIES / 2) {
            entries.entrySet().removeIf(x -> x.getValue().blockedUntil() <= nowMillis
                    && nowMillis - x.getValue().start() >= windowMillis);
            if (entries.size() > MAX_ENTRIES) {
                entries.entrySet().removeIf(x -> x.getValue().blockedUntil() <= nowMillis);
            }
        }
        return e.blockedUntil() == nowMillis + windowMillis && e.failures() == 0;
    }

    /** Warns once when requests come through a proxy whose client addresses RUTERA does not take. */
    public void checkProxy(String forwardedFor) {
        if (!forwardHeaders && forwardedFor != null && proxyWarned.compareAndSet(false, true)) {
            log.warn("Requests carry X-Forwarded-For but RUTERA_FORWARD_HEADERS is not set: every client counts with "
                    + "the proxy's address in the sign-in throttle. Set RUTERA_FORWARD_HEADERS=native when RUTERA is "
                    + "reachable through the proxy only.");
        }
    }

    /** The address, or its /64 network for IPv6. */
    static String key(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String a = address.strip();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        if (!IPV6.matcher(a).matches()) {
            return a;
        }
        try {
            if (InetAddress.getByName(a) instanceof Inet6Address v6) {
                byte[] network = java.util.Arrays.copyOf(v6.getAddress(), 8);
                return HexFormat.of().formatHex(network) + "/64";
            }
        } catch (Exception e) {
            // not a literal address: keep it as it is
        }
        return a;
    }
}
