package com.qqmu.jargus.security;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 票据登录失败限流：同一来源 15 分钟内失败 20 次后暂时拒绝。
 *
 * <p>内存实现，单实例有效；带 key 上限，避免伪造来源耗尽内存。
 */
@Component
public class SsoLoginRateLimiter {

    static final int MAX_FAILURES = 20;
    static final long WINDOW_MS = 15 * 60_000L;
    static final int MAX_KEYS = 10_000;

    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SsoLoginRateLimiter() {
        this(System::currentTimeMillis);
    }

    SsoLoginRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = clock.getAsLong();
        long[] entry = failures.get(key);
        if (entry == null) {
            return false;
        }
        if (now - entry[0] >= WINDOW_MS) {
            failures.remove(key, entry);
            return false;
        }
        return entry[1] >= MAX_FAILURES;
    }

    public void recordFailure(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        long now = clock.getAsLong();
        failures.compute(key, (k, entry) -> {
            if (entry == null || now - entry[0] >= WINDOW_MS) {
                return new long[]{now, 1};
            }
            entry[1] = entry[1] + 1;
            return entry;
        });
        prune(now);
    }

    public void reset(String key) {
        if (key != null && !key.isBlank()) {
            failures.remove(key);
        }
    }

    private void prune(long now) {
        failures.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MS);
        if (failures.size() <= MAX_KEYS) {
            return;
        }
        Iterator<String> it = failures.keySet().iterator();
        while (failures.size() > MAX_KEYS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
