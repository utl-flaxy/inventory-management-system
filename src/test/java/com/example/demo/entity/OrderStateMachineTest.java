package com.example.demo.entity;

import com.example.demo.exception.IllegalOrderStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.demo.TestOrders.*;

class OrderStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING,PENDING,false", "PENDING,SHIPPED,true", "PENDING,COMPLETED,false", "PENDING,CANCELLED,true",
            "SHIPPED,PENDING,false", "SHIPPED,SHIPPED,false", "SHIPPED,COMPLETED,true", "SHIPPED,CANCELLED,false",
            "COMPLETED,PENDING,false", "COMPLETED,SHIPPED,false", "COMPLETED,COMPLETED,false", "COMPLETED,CANCELLED,false",
            "CANCELLED,PENDING,false", "CANCELLED,SHIPPED,false", "CANCELLED,COMPLETED,false", "CANCELLED,CANCELLED,false"
    })
    void allSixteenTransitionsFollowBusinessRules(OrderStatus from, OrderStatus to, boolean allowed) {
        Order order = order(1L, from, OrderItem.create(new Product(1L, "apple", 100L, 10), 1));
        assertEquals(allowed, from.canTransitionTo(to));
        Runnable command = switch (to) {
            case SHIPPED -> order::ship;
            case COMPLETED -> () -> order.complete(java.time.Instant.parse("2026-10-15T12:00:00Z"));
            case CANCELLED -> order::cancel;
            case PENDING -> () -> order.validateTransitionTo(to);
        };

        if (allowed) {
            command.run();
            assertEquals(to, order.getStatus());
        } else {
            assertThrows(IllegalOrderStateTransitionException.class, command::run);
            assertEquals(from, order.getStatus());
        }
    }

    @Test
    void newOrderStartsPending() {
        assertEquals(OrderStatus.PENDING,
                Order.create(customer(), List.of(OrderItem.create(new Product(1L, "apple", 100L, 10), 1))).getStatus());
    }

    @Test
    void nullTargetIsRejectedWithoutChangingState() {
        Order order = order(1L, OrderStatus.PENDING, OrderItem.create(new Product(1L, "apple", 100L, 10), 1));
        assertFalse(OrderStatus.PENDING.canTransitionTo(null));
        assertThrows(IllegalOrderStateTransitionException.class, () -> order.validateTransitionTo(null));
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }
}
