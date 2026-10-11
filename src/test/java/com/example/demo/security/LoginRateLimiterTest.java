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

    @Test
    void exactDefaultPairBoundaryCountsEveryAdmittedAttemptAndCaseVariantsShareQuota() {
        var limiter = limiter();
        for (int i = 0; i < 5; i++) limiter.acquire("source", i % 2 == 0 ? "Alice" : "ALICE");
        assertEquals(60, assertThrows(LoginRateLimitExceededException.class,
                () -> limiter.acquire("source", "alice")).retryAfterSeconds());
        assertEquals(2, limiter.entryCount());
    }

    @Test
    void windowBoundaryAndRetryAfterRoundingAreDeterministicWithoutSleep() {
        properties.setPairLimit(1);
        var limiter = limiter();
        limiter.acquire("source", "alice");
        when(clock.instant()).thenReturn(start.plusSeconds(58).plusNanos(999_999_999));
        assertEquals(2, assertThrows(LoginRateLimitExceededException.class,
                () -> limiter.acquire("source", "alice")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
        assertEquals(1, assertThrows(LoginRateLimitExceededException.class,
                () -> limiter.acquire("source", "alice")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertDoesNotThrow(() -> limiter.acquire("source", "alice"));
    }

    @Test
    void backwardClockDoesNotReopenQuota() {
        properties.setPairLimit(1);
        var limiter = limiter();
        limiter.acquire("source", "alice");
        when(clock.instant()).thenReturn(start.minusSeconds(60));
        assertEquals(60, assertThrows(LoginRateLimitExceededException.class,
                () -> limiter.acquire("source", "alice")).retryAfterSeconds());
    }

    @Test
    void sharedSourceAllowsOtherUserAndSameUsernameAtAnotherSourceWithoutAccountWideLockout() {
        properties.setPairLimit(1);
        var limiter = limiter();
        limiter.acquire("source-one", "alice");
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire("source-one", "alice"));
        assertDoesNotThrow(() -> limiter.acquire("source-one", "bob"));
        assertDoesNotThrow(() -> limiter.acquire("source-two", "alice"));
    }

    @Test
    void rotatingUsernamesCannotBypassSourceQuota() {
        properties.setSourceLimit(3);
        var limiter = limiter();
        for (int i = 0; i < 3; i++) limiter.acquire("same-source", "user-" + i);
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire("same-source", "different"));
        assertEquals(4, limiter.entryCount(), "Rejected requests must not allocate new keys");
        assertDoesNotThrow(() -> limiter.acquire("other-source", "different"));
    }

    @Test
    void rotatingSourcesAndUsernamesCannotBypassGlobalQuota() {
        properties.setGlobalLimit(3);
        var limiter = limiter();
        for (int i = 0; i < 3; i++) limiter.acquire("source-" + i, "user-" + i);
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire("new-source", "new-user"));
        assertEquals(6, limiter.entryCount());
    }

    @Test
    void thousandsOfNewIdentitiesStayWithinCapWithoutEvictingLiveCountersThenExpiredKeysAreRemoved() {
        properties.setMaxEntries(4);
        properties.setPairLimit(1);
        var limiter = limiter();
        limiter.acquire("first", "alice"); limiter.acquire("second", "bob");
        for (int i = 0; i < 10_000; i++) {
            String key = "identity-" + i;
            assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire(key, key));
        }
        assertEquals(4, limiter.entryCount());
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire("first", "alice"));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertDoesNotThrow(() -> limiter.acquire("new", "new"));
        assertEquals(2, limiter.entryCount());
    }

    @Test
    void capacityAdmissionReservesBothKeysAtomicallyAndExistingKeysStillWork() {
        properties.setMaxEntries(3);
        var limiter = limiter();
        limiter.acquire("source", "alice");
        assertThrows(LoginRateLimitExceededException.class, () -> limiter.acquire("new", "bob"));
        assertEquals(2, limiter.entryCount());
        limiter.acquire("source", "bob");
        assertEquals(3, limiter.entryCount());
        assertDoesNotThrow(() -> limiter.acquire("source", "alice"));
    }

    @Test
    void independentCounterExpiriesDoNotPrematurelyResetSourceQuota() {
        properties.setSourceLimit(2);
        var limiter = limiter();
        limiter.acquire("source", "alice");
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        limiter.acquire("source", "bob");
        assertEquals(30, assertThrows(LoginRateLimitExceededException.class,
                () -> limiter.acquire("source", "carol")).retryAfterSeconds());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        limiter.acquire("source", "carol");
        assertEquals(3, limiter.entryCount()); // bob remains until t=90; source/carol until t=120.
    }

    @ParameterizedTest
    @ValueSource(strings = {"pair", "source", "global", "capacity"})
    @Timeout(20)
    void concurrentAdmissionNeverExceedsAnyLimit(String dimension) throws Exception {
        properties.setPairLimit(dimension.equals("pair") ? 3 : 100);
        properties.setSourceLimit(dimension.equals("source") ? 3 : 100);
        properties.setGlobalLimit(dimension.equals("global") ? 3 : 100);
        properties.setMaxEntries(dimension.equals("capacity") ? 6 : 4096);
        var limiter = limiter();
        var executor = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20); var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 20; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    try {
                        limiter.acquire(dimension.equals("global") || dimension.equals("capacity") ? "source-" + index : "source",
                                dimension.equals("pair") ? "alice" : "user-" + index);
                        return true;
                    } catch (LoginRateLimitExceededException ex) { return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
            assertTrue(limiter.entryCount() <= properties.getMaxEntries());
        } finally { go.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pair", "source", "global", "capacity-low", "capacity-high", "window-low", "window-high"})
    void invalidConfigurationFailsClosed(String setting) {
        switch (setting) {
            case "pair" -> properties.setPairLimit(0);
            case "source" -> properties.setSourceLimit(0);
            case "global" -> properties.setGlobalLimit(0);
            case "capacity-low" -> properties.setMaxEntries(1);
            case "capacity-high" -> properties.setMaxEntries(100_001);
            case "window-low" -> properties.setWindow(Duration.ofMillis(999));
            default -> properties.setWindow(Duration.ofDays(1).plusNanos(1));
        }
        assertThrows(IllegalArgumentException.class, this::limiter);
    }
}
