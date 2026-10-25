package com.example.demo.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static com.example.demo.TestOrders.customer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderTimestampPrecisionTest {
    private Order order() {
        return Order.create(customer(), List.of(OrderItem.create(new Product(1L, "apple", 100L, 10), 1)));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-25T01:02:03.123456789Z,2026-10-25T01:02:03.123456Z",
            "2026-10-25T01:02:03.999999999Z,2026-10-25T01:02:03.999999Z",
            "2026-10-25T01:02:03.123456Z,2026-10-25T01:02:03.123456Z"
    })
    void creationFloorsToMicrosRatherThanRounding(String source, String expected) {
        Instant raw = Instant.parse(source);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(raw);
            Order order = order();
            assertEquals(Instant.parse(expected), order.getOrderedAt());
            assertEquals(order.getOrderedAt(), order.getUpdatedAt());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-25T01:02:04.123456789Z,2026-10-25T01:02:04.123456Z",
            "2026-10-25T01:02:04.999999999Z,2026-10-25T01:02:04.999999Z",
            "2026-10-25T01:02:04.123456Z,2026-10-25T01:02:04.123456Z"
    })
    void preUpdateUsesItsCurrentInstantAndNeverRewritesOrderedAt(String source, String expected) {
        Instant created = Instant.parse("2026-10-25T01:02:03.111111Z");
        Instant raw = Instant.parse(source);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(created);
            Order order = order();
            order.ship();
            assertEquals(created, order.getUpdatedAt()); // A domain command alone still does not invoke the callback.
            time.when(Instant::now).thenReturn(raw);
            ReflectionTestUtils.invokeMethod(order, "updateTimestamp");
            assertEquals(created, order.getOrderedAt());
            assertEquals(Instant.parse(expected), order.getUpdatedAt());
            assertNull(order.getCompletedAt());
        }
    }

    @Test
    void completionStillPreservesTheExplicitDomainArgument() {
        Order order = order();
        order.ship();
        Instant supplied = Instant.parse("2026-10-25T01:02:05.999999999Z");
        order.complete(supplied);
        assertEquals(supplied, order.getCompletedAt()); // HTTP Service already applies its existing MICROS policy.
    }
}
