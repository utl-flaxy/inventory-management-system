package com.example.demo.entity;

import com.example.demo.exception.IllegalOrderStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static com.example.demo.TestOrders.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderCompletionTest {
    private static final Instant COMPLETION = Instant.parse("2026-10-15T12:00:00.123456Z");

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void timestampMatchesStatusAndCannotBeChangedThroughAnotherCommand(OrderStatus status) {
        Order order = order(1L, status, OrderItem.create(new Product(1L, "apple", 100L, 10), 1));
        Instant previous = order.getCompletedAt();
        assertEquals(status == OrderStatus.COMPLETED, previous != null);
        if (status == OrderStatus.SHIPPED) {
            order.complete(COMPLETION);
            assertEquals(COMPLETION, order.getCompletedAt());
            assertThrows(IllegalOrderStateTransitionException.class, () -> order.complete(COMPLETION.plusSeconds(1)));
            assertEquals(COMPLETION, order.getCompletedAt());
        } else {
            assertThrows(IllegalOrderStateTransitionException.class, () -> order.complete(COMPLETION));
            assertEquals(previous, order.getCompletedAt());
            assertEquals(status, order.getStatus());
        }
    }

    @Test
    void nullCompletionTimeLeavesShippedOrderUnchanged() {
        Order order = order(1L, OrderStatus.SHIPPED, OrderItem.create(new Product(1L, "apple", 100L, 10), 1));
        assertThrows(IllegalArgumentException.class, () -> order.complete(null));
        assertEquals(OrderStatus.SHIPPED, order.getStatus());
        assertNull(order.getCompletedAt());
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void persistenceCallbacksRejectInconsistentCompletionTimestamp(OrderStatus status) {
        Order order = order(1L, status, OrderItem.create(new Product(1L, "apple", 100L, 10), 1));
        ReflectionTestUtils.setField(order, "completedAt", status == OrderStatus.COMPLETED ? null : COMPLETION);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(order, "validateAggregate"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(order, "updateTimestamp"));
    }
}
