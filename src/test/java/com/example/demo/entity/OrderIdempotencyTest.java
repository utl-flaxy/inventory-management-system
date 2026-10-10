package com.example.demo.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static com.example.demo.TestOrders.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderIdempotencyTest {
    private static final String HASH = "a".repeat(64);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "g000000000000000000000000000000000000000000000000000000000000000"})
    void keyedFactoryRequiresValidFingerprint(String fingerprint) {
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), items(), "key", fingerprint));
    }

    @Test
    void keyedFactoryRequiresKeyAndAccepts128AsciiCharacters() {
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), items(), null, HASH));
        Order order = Order.create(customer(), items(), "a".repeat(128), HASH);
        assertEquals("a".repeat(128), order.getIdempotencyKey());
        assertEquals(HASH, order.getRequestFingerprint());
        ReflectionTestUtils.invokeMethod(order, "validateAggregate");
    }

    @Test
    void legacyNullIdentityRemainsValidDuringShipmentCompletionAndCancellation() {
        Order order = Order.create(customer(), items());
        order.ship();
        ReflectionTestUtils.invokeMethod(order, "updateTimestamp");
        order.complete(Instant.parse("2026-10-15T12:00:00Z"));
        ReflectionTestUtils.invokeMethod(order, "updateTimestamp");
        assertNull(order.getIdempotencyKey());
        assertNull(order.getRequestFingerprint());
        Order cancelled = Order.create(customer(), items());
        cancelled.cancel();
        ReflectionTestUtils.invokeMethod(cancelled, "updateTimestamp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"idempotencyKey", "requestFingerprint"})
    void callbacksRejectHalfMissingIdentity(String missing) {
        Order order = Order.create(customer(), items(), "key", HASH);
        ReflectionTestUtils.setField(order, missing, null);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(order, "validateAggregate"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(order, "updateTimestamp"));
    }

    private List<OrderItem> items() { return List.of(OrderItem.create(new Product(1L, "apple", 100L, 10), 1)); }
}
