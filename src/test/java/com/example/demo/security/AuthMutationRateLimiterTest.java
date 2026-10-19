package com.example.demo.security;

import com.example.demo.exception.AuthMutationRateLimitExceededException;
import com.example.demo.exception.AuthMutationSourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthMutationRateLimiterTest {
    private final Instant start = Instant.parse("2026-10-17T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final AuthMutationRateLimitProperties properties = new AuthMutationRateLimitProperties();

    private AuthMutationRateLimiter limiter() {
        when(clock.instant()).thenReturn(start);
        return new AuthMutationRateLimiter(properties, clock);
    }

    private void admit(AuthMutationRateLimiter limiter, String source) {
        try (var permit = limiter.acquire(source)) { /* completed or failed authentication mutation */ }
    }

    @Test
    void defaultFiveAttemptsAreSourceOnlyAndOtherSourcesRemainIndependent() {
        var limiter = limiter();
        for (int i = 0; i < 5; i++) admit(limiter, "198.51.100.1");
        assertEquals(60, assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "198.51.100.1")).retryAfterSeconds());
        assertEquals(1, limiter.entryCount());
        admit(limiter, "198.51.100.2");
    }

    @Test
    void exactBoundaryRoundingAndBackwardClockDoNotReopenWindow() {
        properties.setSourceLimit(1);
        var limiter = limiter();
        admit(limiter, "198.51.100.1");
        when(clock.instant()).thenReturn(start.minusSeconds(60));
        assertEquals(60, assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "198.51.100.1")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(58).plusNanos(999_999_999));
        assertEquals(2, assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "198.51.100.1")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
        assertEquals(1, assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "198.51.100.1")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        admit(limiter, "198.51.100.1");
        assertEquals(1, limiter.entryCount());
    }

    @Test
    void twoDefaultPermitsRejectWithoutQueueOrQuotaAllocationThenRecoverImmediately() {
        var limiter = limiter();
        try (var first = limiter.acquire("198.51.100.1"); var second = limiter.acquire("198.51.100.2")) {
            assertEquals(2, limiter.inFlightCount());
            assertEquals(1, assertThrows(AuthMutationRateLimitExceededException.class,
                    () -> admit(limiter, "198.51.100.3")).retryAfterSeconds());
            assertEquals(2, limiter.entryCount());
        }
        for (int i = 0; i < 5; i++) admit(limiter, "198.51.100.3");
        assertEquals(0, limiter.inFlightCount());
    }

    @Test
    void exceptionalExitAndRepeatedCloseReleaseExactlyOnce() {
        properties.setMaxConcurrent(1);
        var limiter = limiter();
        var permit = limiter.acquire("198.51.100.1");
        assertThrows(IllegalStateException.class, () -> {
            try (permit) { throw new IllegalStateException("test failure"); }
        });
        permit.close();
        try (var next = limiter.acquire("198.51.100.2")) {
            permit.close();
            assertEquals(1, limiter.inFlightCount());
            assertThrows(AuthMutationRateLimitExceededException.class,
                    () -> admit(limiter, "198.51.100.3"));
        }
        assertEquals(0, limiter.inFlightCount());
    }

    @Test
    void stateChurnIsBoundedEvictsOldestWholeWindowAndExpiredStateIsRemoved() {
        properties.setMaxEntries(2);
        properties.setSourceLimit(1);
        var limiter = limiter();
        admit(limiter, "198.51.100.1");
        for (int i = 0; i < 10_000; i++) admit(limiter, "2001:db8::" + Integer.toHexString(i + 1));
        assertEquals(2, limiter.entryCount());
        // Eviction permits old sources again: documented distributed-churn limitation, not global lockout.
        admit(limiter, "198.51.100.1");
        for (int i = 0; i < 1000; i++) assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "198.51.100.1"));
        assertEquals(2, limiter.entryCount());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        admit(limiter, "198.51.100.2");
        assertEquals(1, limiter.entryCount());
    }

    @Test
    void ipv6AndMappedAliasesShareOneCanonicalSourceWindow() {
        properties.setSourceLimit(1);
        var limiter = limiter();
        admit(limiter, "2001:db8::1");
        assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "2001:0db8:0:0:0:0:0:1"));
        admit(limiter, "198.51.100.1");
        assertThrows(AuthMutationRateLimitExceededException.class,
                () -> admit(limiter, "::ffff:198.51.100.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "localhost", "", "198.51.100.1, 198.51.100.2"})
    void invalidSourcesNeverAllocateStateOrPermit(String source) {
        var limiter = limiter();
        assertThrows(AuthMutationSourceUnavailableException.class, () -> limiter.acquire(source));
        assertThrows(AuthMutationSourceUnavailableException.class, () -> limiter.acquire(null));
        assertEquals(0, limiter.entryCount());
        assertEquals(0, limiter.inFlightCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "concurrent", "capacity"})
    @Timeout(20)
    void concurrentAdmissionIsAtomicForSourceWorkAndStateBounds(String dimension) throws Exception {
        properties.setSourceLimit(dimension.equals("source") ? 3 : 100);
        properties.setMaxConcurrent(dimension.equals("concurrent") ? 3 : 20);
        properties.setMaxEntries(dimension.equals("capacity") ? 2 : 4096);
        var limiter = limiter();
        var executor = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20); var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        var held = new ConcurrentLinkedQueue<AuthMutationRateLimiter.Permit>();
        try {
            for (int i = 1; i <= 20; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    try {
                        String source = "198.51.100." + (dimension.equals("source") ? 1 : index);
                        if (dimension.equals("concurrent")) held.add(limiter.acquire(source));
                        else admit(limiter, source);
                        return true;
                    } catch (AuthMutationRateLimitExceededException ex) { return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(dimension.equals("capacity") ? 20 : 3, accepted);
            assertTrue(limiter.entryCount() <= properties.getMaxEntries());
            held.forEach(AuthMutationRateLimiter.Permit::close);
            assertEquals(0, limiter.inFlightCount());
        } finally {
            go.countDown(); held.forEach(AuthMutationRateLimiter.Permit::close);
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "concurrent-low", "concurrent-high", "entries-low", "entries-high",
            "window-low", "window-high", "window-null"})
    void invalidSettingsFailClosed(String setting) {
        switch (setting) {
            case "source" -> properties.setSourceLimit(0);
            case "concurrent-low" -> properties.setMaxConcurrent(0);
            case "concurrent-high" -> properties.setMaxConcurrent(65);
            case "entries-low" -> properties.setMaxEntries(0);
            case "entries-high" -> properties.setMaxEntries(100_001);
            case "window-low" -> properties.setWindow(Duration.ofMillis(999));
            case "window-high" -> properties.setWindow(Duration.ofDays(1).plusNanos(1));
            default -> properties.setWindow(null);
        }
        assertThrows(IllegalArgumentException.class, this::limiter);
    }
}
