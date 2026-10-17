package com.example.demo.security;

import com.example.demo.exception.RegistrationRateLimitExceededException;
import com.example.demo.exception.RegistrationSourceUnavailableException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Registration-only admission: no account keys, process-wide historical quota, queue or background thread. */
@Component
public class RegistrationRateLimiter {
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
    private final LinkedHashMap<String, SourceWindow> sources = new LinkedHashMap<>();
    private int inFlight;
    private Instant lastNow = Instant.MIN;

    public RegistrationRateLimiter(RegistrationRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.clock = clock;
        window = properties.getWindow();
        sourceLimit = properties.getSourceLimit();
        maxConcurrent = properties.getMaxConcurrent();
        maxEntries = properties.getMaxEntries();
    }

    public Permit acquire(String source) {
        // Only literal, bounded-length identities captured by the transport filter may allocate state.
        String address = LoginClientAddressFilter.literalAddress(source);
        if (address == null) throw new RegistrationSourceUnavailableException();
        synchronized (lock) {
            Instant now = clock.instant();
            if (now.isBefore(lastNow)) now = lastNow;
            lastNow = now;
            var expired = sources.entrySet().iterator();
            while (expired.hasNext()) {
                if (expired.next().getValue().expires.isAfter(now)) break;
                expired.remove();
            }
            SourceWindow counter = sources.get(address);
            if (counter != null && counter.count >= sourceLimit) {
                Duration remaining = Duration.between(now, counter.expires);
                throw new RegistrationRateLimitExceededException(
                        remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
            }
            if (inFlight >= maxConcurrent) throw new RegistrationRateLimitExceededException(1);
            if (counter == null) {
                // Do not turn bounded historical state into a service-wide registration lockout.
                if (sources.size() == maxEntries) sources.remove(sources.keySet().iterator().next());
                counter = new SourceWindow(now.plus(window));
                sources.put(address, counter);
            }
            counter.count++;
            inFlight++;
            return new Permit();
        }
    }

    int entryCount() { synchronized (lock) { return sources.size(); } }
    int inFlightCount() { synchronized (lock) { return inFlight; } }
}
