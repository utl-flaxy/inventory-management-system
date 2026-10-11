package com.example.demo.security;

import com.example.demo.exception.LoginRateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Single-process, atomic admission; no DB writes and no eviction of live counters. */
@Component
public class LoginRateLimiter {
    private record Key(String source, String username) { }
    private static final class Counter {
        private final Instant expires;
        private int count;
        private Counter(Instant expires) { this.expires = expires; }
    }

    private final Clock clock;
    private final Duration window;
    private final int pairLimit, sourceLimit, globalLimit, maxEntries;
    private final LinkedHashMap<Key, Counter> counters = new LinkedHashMap<>();
    private Counter global;
    private Instant lastNow = Instant.MIN;

    public LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.clock = clock;
        window = properties.getWindow();
        pairLimit = properties.getPairLimit();
        sourceLimit = properties.getSourceLimit();
        globalLimit = properties.getGlobalLimit();
        maxEntries = properties.getMaxEntries();
    }

    public synchronized void acquire(String source, String username) {
        Instant now = clock.instant();
        if (now.isBefore(lastNow)) now = lastNow; // A backward clock adjustment must not reset limits.
        lastNow = now;
        // Equal TTL and monotonic admission time keep insertion order in expiry order.
        var iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expires.isAfter(now)) break;
            iterator.remove();
        }
        if (global == null || !global.expires.isAfter(now)) global = new Counter(now.plus(window));
        Key sourceKey = new Key(source, null);
        // The existing MariaDB username collation is case insensitive; casing cannot reset a pair quota.
        Key pairKey = new Key(source, username.toLowerCase(Locale.ROOT));
        Counter sourceCounter = counters.get(sourceKey), pairCounter = counters.get(pairKey);
        Instant blockedUntil = now;
        if (global.count >= globalLimit) blockedUntil = global.expires;
        if (sourceCounter != null && sourceCounter.count >= sourceLimit)
            blockedUntil = later(blockedUntil, sourceCounter.expires);
        if (pairCounter != null && pairCounter.count >= pairLimit)
            blockedUntil = later(blockedUntil, pairCounter.expires);
        int required = (sourceCounter == null ? 1 : 0) + (pairCounter == null ? 1 : 0);
        if (counters.size() + required > maxEntries)
            blockedUntil = later(blockedUntil, counters.values().iterator().next().expires);
        if (blockedUntil.isAfter(now)) {
            Duration remaining = Duration.between(now, blockedUntil);
            throw new LoginRateLimitExceededException(remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
        }
        if (sourceCounter == null) {
            sourceCounter = new Counter(now.plus(window)); counters.put(sourceKey, sourceCounter);
        }
        if (pairCounter == null) {
            pairCounter = new Counter(now.plus(window)); counters.put(pairKey, pairCounter);
        }
        global.count++; sourceCounter.count++; pairCounter.count++;
    }

    private Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }

    synchronized int entryCount() { return counters.size(); }
}
