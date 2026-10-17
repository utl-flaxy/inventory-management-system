package com.example.demo.security;

import com.example.demo.exception.LoginRateLimitExceededException;
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

class LoginRateLimiterTest {
    private final Instant start = Instant.parse("2026-10-11T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final LoginRateLimitProperties properties = new LoginRateLimitProperties();

    private LoginRateLimiter limiter() {
        when(clock.instant()).thenReturn(start);
        return new LoginRateLimiter(properties, clock);
    }

    private void admit(LoginRateLimiter limiter, String source, String username) {
        try (var permit = limiter.acquire(source, username)) { /* completed authentication */ }
    }

    @Test
    void exactDefaultPairBoundaryCountsEveryAdmittedAttemptAndCaseVariantsShareQuota() {
        var limiter = limiter();
        for (int i = 0; i < 5; i++) admit(limiter, "source", i % 2 == 0 ? "Alice" : "ALICE");
        assertEquals(60, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "source", "alice")).retryAfterSeconds());
        assertEquals(2, limiter.entryCount());
    }

    @Test
    void windowBoundaryAndRetryAfterRoundingAreDeterministicWithoutSleep() {
        properties.setPairLimit(1);
        var limiter = limiter();
        admit(limiter, "source", "alice");
        when(clock.instant()).thenReturn(start.plusSeconds(58).plusNanos(999_999_999));
        assertEquals(2, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "source", "alice")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
        assertEquals(1, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "source", "alice")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertDoesNotThrow(() -> admit(limiter, "source", "alice"));
    }

    @Test
    void backwardClockDoesNotReopenQuota() {
        properties.setPairLimit(1);
        var limiter = limiter();
        admit(limiter, "source", "alice");
        when(clock.instant()).thenReturn(start.minusSeconds(60));
        assertEquals(60, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "source", "alice")).retryAfterSeconds());
    }

    @Test
    void sharedSourceAllowsOtherUserAndSameUsernameAtAnotherSourceWithoutAccountWideLockout() {
        properties.setPairLimit(1);
        var limiter = limiter();
        admit(limiter, "source-one", "alice");
        assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "source-one", "alice"));
        assertDoesNotThrow(() -> admit(limiter, "source-one", "bob"));
        assertDoesNotThrow(() -> admit(limiter, "source-two", "alice"));
    }

    @Test
    void rotatingUsernamesCannotBypassSourceQuota() {
        properties.setSourceLimit(3);
        var limiter = limiter();
        for (int i = 0; i < 3; i++) admit(limiter, "same-source", "user-" + i);
        assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "same-source", "different"));
        assertEquals(4, limiter.entryCount(), "Rejected requests must not allocate new keys");
        assertDoesNotThrow(() -> admit(limiter, "other-source", "different"));
    }

    @Test
    void rotatingSourcesAndUsernamesCannotBypassInFlightBoundButCompletionImmediatelyRecovers() {
        properties.setMaxConcurrent(3);
        var limiter = limiter();
        var permits = new ArrayList<LoginRateLimiter.Permit>();
        for (int i = 0; i < 3; i++) permits.add(limiter.acquire("source-" + i, "user-" + i));
        assertEquals(1, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "new-source", "new-user")).retryAfterSeconds());
        assertEquals(6, limiter.entryCount());
        permits.forEach(LoginRateLimiter.Permit::close);
        assertDoesNotThrow(() -> admit(limiter, "new-source", "new-user"));
        assertEquals(0, limiter.inFlightCount());
    }

    @Test
    void thousandsOfNewIdentitiesStayWithinCapWithoutLockingOutNewSourcesThenExpiredKeysAreRemoved() {
        properties.setMaxEntries(4);
        properties.setPairLimit(1);
        var limiter = limiter();
        admit(limiter, "first", "alice"); admit(limiter, "second", "bob");
        for (int i = 0; i < 10_000; i++) {
            String key = "identity-" + i;
            assertDoesNotThrow(() -> admit(limiter, key, key));
        }
        assertEquals(4, limiter.entryCount());
        // Distributed churn can evict older source windows, but cannot create a global denial.
        admit(limiter, "first", "alice");
        assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "first", "alice"));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertDoesNotThrow(() -> admit(limiter, "new", "new"));
        assertEquals(2, limiter.entryCount());
    }

    @Test
    void capacityAdmissionReservesBothKeysAtomicallyAndExistingKeysStillWork() {
        properties.setMaxEntries(3);
        var limiter = limiter();
        admit(limiter, "source", "alice");
        assertDoesNotThrow(() -> admit(limiter, "new", "bob"));
        assertEquals(2, limiter.entryCount());
        admit(limiter, "new", "alice");
        assertEquals(3, limiter.entryCount());
        assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "new", "carol"));
        assertDoesNotThrow(() -> admit(limiter, "new", "alice"));
    }

    @Test
    void independentCounterExpiriesDoNotPrematurelyResetSourceQuota() {
        properties.setSourceLimit(2);
        var limiter = limiter();
        admit(limiter, "source", "alice");
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        admit(limiter, "source", "bob");
        assertEquals(30, assertThrows(LoginRateLimitExceededException.class,
                () -> admit(limiter, "source", "carol")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        admit(limiter, "source", "carol");
        assertEquals(2, limiter.entryCount()); // The whole source window expires at t=60, never on username churn.
    }

    @Test
    void completedFailuresAcrossThousandsOfSourcesHaveNoHistoricalGlobalDebt() {
        var limiter = limiter();
        for (int i = 0; i < 10_000; i++) admit(limiter, "source-" + i, "alice");
        assertDoesNotThrow(() -> admit(limiter, "victim-source", "alice"));
        assertTrue(limiter.entryCount() <= properties.getMaxEntries());
    }

    @Test
    void permitIsReleasedOnceEvenOnExceptionalExitAndRepeatedClose() {
        properties.setMaxConcurrent(1);
        var limiter = limiter();
        var permit = limiter.acquire("attacker", "alice");
        assertThrows(IllegalStateException.class, () -> {
            try (permit) { throw new IllegalStateException("safe test failure"); }
        });
        permit.close();
        assertEquals(0, limiter.inFlightCount());
        try (var next = limiter.acquire("victim", "alice")) {
            permit.close();
            assertEquals(1, limiter.inFlightCount());
            assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "other", "alice"));
        }
        assertEquals(0, limiter.inFlightCount());
    }

    @Test
    void rotatingUsernamesAtFullCapacityCannotEvictThatSourcesOwnCounter() {
        properties.setMaxEntries(3);
        var limiter = limiter();
        admit(limiter, "attacker", "one");
        admit(limiter, "attacker", "two");
        for (int i = 0; i < 1000; i++) {
            String user = "rotated-" + i;
            assertThrows(LoginRateLimitExceededException.class, () -> admit(limiter, "attacker", user));
        }
        assertDoesNotThrow(() -> admit(limiter, "victim", "one"));
        assertTrue(limiter.entryCount() <= 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pair", "source", "global", "capacity"})
    @Timeout(20)
    void concurrentAdmissionNeverExceedsAnyLimit(String dimension) throws Exception {
        properties.setPairLimit(dimension.equals("pair") ? 3 : 100);
        properties.setSourceLimit(dimension.equals("source") ? 3 : 100);
        properties.setMaxConcurrent(dimension.equals("global") ? 3 : 20);
        properties.setMaxEntries(dimension.equals("capacity") ? 6 : 4096);
        var limiter = limiter();
        var executor = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20); var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        var held = new ConcurrentLinkedQueue<LoginRateLimiter.Permit>();
        try {
            for (int i = 0; i < 20; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    try {
                        String source = dimension.equals("global") || dimension.equals("capacity") ? "source-" + index : "source";
                        String user = dimension.equals("pair") ? "alice" : "user-" + index;
                        if (dimension.equals("global")) held.add(limiter.acquire(source, user));
                        else admit(limiter, source, user);
                        return true;
                    } catch (LoginRateLimitExceededException ex) { return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(dimension.equals("capacity") ? 20 : 3, accepted);
            assertTrue(limiter.entryCount() <= properties.getMaxEntries());
            held.forEach(LoginRateLimiter.Permit::close);
            assertEquals(0, limiter.inFlightCount());
        } finally { go.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pair", "source", "global", "capacity-low", "capacity-high", "window-low", "window-high",
            "concurrent-low", "concurrent-high", "legacy-global"})
    void invalidConfigurationFailsClosed(String setting) {
        switch (setting) {
            case "pair" -> properties.setPairLimit(0);
            case "source" -> properties.setSourceLimit(0);
            case "global" -> properties.setGlobalLimit(0);
            case "legacy-global" -> properties.setGlobalLimit(1000);
            case "concurrent-low" -> properties.setMaxConcurrent(0);
            case "concurrent-high" -> properties.setMaxConcurrent(65);
            case "capacity-low" -> properties.setMaxEntries(1);
            case "capacity-high" -> properties.setMaxEntries(100_001);
            case "window-low" -> properties.setWindow(Duration.ofMillis(999));
            default -> properties.setWindow(Duration.ofDays(1).plusNanos(1));
        }
        assertThrows(IllegalArgumentException.class, this::limiter);
    }
}
