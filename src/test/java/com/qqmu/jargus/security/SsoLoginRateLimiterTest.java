package com.qqmu.jargus.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoLoginRateLimiterTest {

    @Test
    void blocksOnTwentiethFailure() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SsoLoginRateLimiter limiter = new SsoLoginRateLimiter(now::get);

        for (int i = 0; i < 19; i++) {
            limiter.recordFailure("1.2.3.4");
            assertFalse(limiter.isBlocked("1.2.3.4"), "第 " + (i + 1) + " 次不应拦截");
        }
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));
        assertFalse(limiter.isBlocked("5.6.7.8"));
    }

    @Test
    void windowExpiryUnblocks() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SsoLoginRateLimiter limiter = new SsoLoginRateLimiter(now::get);
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("ip");
        }
        assertTrue(limiter.isBlocked("ip"));
        now.addAndGet(SsoLoginRateLimiter.WINDOW_MS);
        assertFalse(limiter.isBlocked("ip"));
    }

    @Test
    void resetClearsFailures() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SsoLoginRateLimiter limiter = new SsoLoginRateLimiter(now::get);
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("ip");
        }
        limiter.reset("ip");
        assertFalse(limiter.isBlocked("ip"));
    }

    @Test
    void blankKeysAreIgnored() {
        SsoLoginRateLimiter limiter = new SsoLoginRateLimiter(() -> 1L);
        limiter.recordFailure(null);
        limiter.recordFailure("  ");
        assertFalse(limiter.isBlocked(null));
        assertFalse(limiter.isBlocked(""));
    }
}
