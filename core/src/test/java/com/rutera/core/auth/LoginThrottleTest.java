package com.rutera.core.auth;

import com.rutera.core.security.RuteraSecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The sign-in throttle without Spring: block length, the end of a block, IPv6 networks. */
class LoginThrottleTest {

    private static final long MINUTE = 60_000;

    private static LoginThrottle throttle(int maxFailures) {
        return new LoginThrottle(new RuteraSecurityProperties(5, Duration.ofMinutes(15), 4, 10, maxFailures,
                Duration.ofMinutes(15), Duration.ofHours(12), null), "none");
    }

    @Test
    void theBlockLastsAWholeWindowFromTheLastFailure() {
        LoginThrottle t = throttle(3);
        long start = 1_000_000_000L;
        assertThat(t.failed("203.0.113.7", start)).isFalse();
        assertThat(t.failed("203.0.113.7", start + 10 * MINUTE)).isFalse();
        assertThat(t.retryAfter("203.0.113.7", start + 14 * MINUTE)).isZero();
        assertThat(t.failed("203.0.113.7", start + 14 * MINUTE)).as("third failure blocks").isTrue();
        assertThat(t.retryAfter("203.0.113.7", start + 15 * MINUTE))
                .as("not just until the first window ends").isEqualTo(14 * 60);
        assertThat(t.retryAfter("203.0.113.7", start + 29 * MINUTE - 500)).isEqualTo(1);
        assertThat(t.retryAfter("203.0.113.7", start + 29 * MINUTE)).isZero();
        assertThat(t.failed("203.0.113.7", start + 30 * MINUTE)).as("counting starts again").isFalse();
        assertThat(t.retryAfter("203.0.113.7", start + 30 * MINUTE)).isZero();
        assertThat(t.retryAfter("198.51.100.1", start + 15 * MINUTE)).as("other addresses").isZero();
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        LoginThrottle t = throttle(3);
        long start = 2_000_000_000L;
        t.failed("203.0.113.8", start);
        t.failed("203.0.113.8", start + MINUTE);
        assertThat(t.failed("203.0.113.8", start + 16 * MINUTE)).isFalse();
        assertThat(t.retryAfter("203.0.113.8", start + 16 * MINUTE)).isZero();
    }

    @Test
    void ipv6CountsPerNetwork() {
        assertThat(LoginThrottle.key("2001:db8:1:2:aaaa::1")).isEqualTo(LoginThrottle.key("2001:db8:1:2:bbbb::9"))
                .isEqualTo("20010db800010002/64");
        assertThat(LoginThrottle.key("[2001:db8:1:3::1]")).isEqualTo("20010db800010003/64");
        assertThat(LoginThrottle.key("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(LoginThrottle.key(null)).isEqualTo("unknown");
        LoginThrottle t = throttle(2);
        t.failed("2001:db8:1:2::1", 0);
        assertThat(t.failed("2001:db8:1:2::2", 1)).as("same /64").isTrue();
        assertThat(t.retryAfter("2001:db8:1:2:ffff::3", 2)).isPositive();
    }
}
