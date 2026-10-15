package com.example.demo.security;

import com.example.demo.exception.LoginRateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Atomic per-source admission plus nonblocking, short-lived authentication work permits. */
@Component
public class LoginRateLimiter {
    private static final class SourceWindow {
        private final Instant expires;
        private final Map<String, Integer> usernames = new HashMap<>();
        private int count;
        private SourceWindow(Instant expires) { this.expires = expires; }
        private int size() { return 1 + usernames.size(); }
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        private Permit() { }

        @Override
        public void close() {
            synchronized (LoginRateLimiter.this) {
                if (!closed) { inFlight--; closed = true; }
            }
        }
    }

    private final Clock clock;
    private final Duration window;
    private final int pairLimit, sourceLimit, maxConcurrent, maxEntries;
    // Insertion order is source-window age, not username churn. Evict a whole oldest source.
    private final LinkedHashMap<String, SourceWindow> sources = new LinkedHashMap<>();
    private int entries, inFlight;
    private Instant lastNow = Instant.MIN;

    public LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.clock = clock;
        window = properties.getWindow();
        pairLimit = properties.getPairLimit();
        sourceLimit = properties.getSourceLimit();
        maxConcurrent = properties.getMaxConcurrent();
        maxEntries = properties.getMaxEntries();
    }

    public synchronized Permit acquire(String source, String username) {
        Instant now = clock.instant();
        if (now.isBefore(lastNow)) now = lastNow;
        lastNow = now;
        var expired = sources.entrySet().iterator();
        while (expired.hasNext()) {
            var entry = expired.next();
            if (entry.getValue().expires.isAfter(now)) break;
            entries -= entry.getValue().size();
            expired.remove();
        }
        SourceWindow counter = sources.get(source);
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        int pairCount = counter == null ? 0 : counter.usernames.getOrDefault(normalizedUsername, 0);
        if (counter != null && (counter.count >= sourceLimit || pairCount >= pairLimit))
            throw limitedUntil(now, counter.expires);
        // No queue and no time-window debt for other sources after authentication finishes.
        if (inFlight >= maxConcurrent) throw new LoginRateLimitExceededException(1);
        int required = (counter == null ? 1 : 0) + (pairCount == 0 ? 1 : 0);
        // A source cannot evict its own counters by rotating usernames.
        if (counter != null && counter.size() + required > maxEntries)
            throw limitedUntil(now, counter.expires);
        var oldest = sources.entrySet().iterator();
        while (entries + required > maxEntries && oldest.hasNext()) {
            var entry = oldest.next();
            if (entry.getKey().equals(source)) continue;
            entries -= entry.getValue().size();
            oldest.remove();
        }
        if (counter == null) {
            counter = new SourceWindow(now.plus(window));
            sources.put(source, counter);
        }
        entries += required;
        counter.count++;
        counter.usernames.put(normalizedUsername, pairCount + 1);
        inFlight++;
        return new Permit();
    }

    private LoginRateLimitExceededException limitedUntil(Instant now, Instant expires) {
        Duration remaining = Duration.between(now, expires);
        return new LoginRateLimitExceededException(remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
    }

    synchronized int entryCount() { return entries; }
    synchronized int inFlightCount() { return inFlight; }
}
