package com.example.demo.security;

import com.example.demo.exception.AuthMutationRateLimitExceededException;
import com.example.demo.exception.AuthMutationSourceUnavailableException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Per-source/operation windows, with one shared bounded concurrency permit for all auth mutations. */
@Component
public class AuthMutationRateLimiter {
    public enum Operation { LOGOUT_ALL, PASSWORD_CHANGE, FORCE_REVOKE }
    private record SourceOperation(String address, Operation operation) { }
    private static final class SourceWindow {
        private final Instant expires;
        private int count;
        private SourceWindow(Instant expires) { this.expires = expires; }
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        private Permit() { }
        @Override public void close() {
            synchronized (lock) {
                if (!closed) { inFlight--; closed = true; }
            }
        }
    }

    private final Object lock = new Object();
    private final Clock clock;
    private final Duration window;
    private final int sourceLimit, maxConcurrent, maxEntries;
    private final LinkedHashMap<SourceOperation, SourceWindow> sources = new LinkedHashMap<>();
    private int inFlight;
    private Instant lastNow = Instant.MIN;

    public AuthMutationRateLimiter(AuthMutationRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.clock = clock;
        window = properties.getWindow();
        sourceLimit = properties.getSourceLimit();
        maxConcurrent = properties.getMaxConcurrent();
        maxEntries = properties.getMaxEntries();
    }

    public Permit acquire(String source) {
        return acquire(Operation.LOGOUT_ALL, source);
    }

    public Permit acquire(Operation operation, String source) {
        java.util.Objects.requireNonNull(operation);
        // Only literal, bounded-length identities captured by the transport filter may allocate state.
        String address = LoginClientAddressFilter.literalAddress(source);
        if (address == null) throw new AuthMutationSourceUnavailableException();
        var key = new SourceOperation(address, operation);
        synchronized (lock) {
            Instant now = clock.instant();
            if (now.isBefore(lastNow)) now = lastNow;
            lastNow = now;
            var expired = sources.entrySet().iterator();
            while (expired.hasNext()) {
                if (expired.next().getValue().expires.isAfter(now)) break;
                expired.remove();
            }
            SourceWindow counter = sources.get(key);
            if (counter != null && counter.count >= sourceLimit) {
                Duration remaining = Duration.between(now, counter.expires);
                throw new AuthMutationRateLimitExceededException(
                        remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
            }
            if (inFlight >= maxConcurrent) throw new AuthMutationRateLimitExceededException(1);
            if (counter == null) {
                // Do not turn bounded historical state into a service-wide authentication mutation lockout.
                if (sources.size() == maxEntries) sources.remove(sources.keySet().iterator().next());
                counter = new SourceWindow(now.plus(window));
                sources.put(key, counter);
            }
            counter.count++;
            inFlight++;
            return new Permit();
        }
    }

    int entryCount() { synchronized (lock) { return sources.size(); } }
    int inFlightCount() { synchronized (lock) { return inFlight; } }
}
