package com.example.demo.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.example.demo.TestOrders.customer;
import static org.junit.jupiter.api.Assertions.*;

class OrderAggregateTest {
    private Product apple() {
        return new Product(1L, "apple", 100, 10);
    }

    @Test
    void creationSetsCustomerStateTimestampsVersionAndBothSidesOfEachLine() {
        Instant before = Instant.now();
        User customer = customer();
        OrderItem first = OrderItem.create(apple(), 2);
        OrderItem second = OrderItem.create(new Product(2L, "orange", 300, 5), 1);
        Order order = Order.create(customer, List.of(first, second));

        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertSame(customer, order.getCustomer());
        assertEquals(List.of(first, second), order.getOrderItems());
        assertSame(order, first.getOrder());
        assertSame(order, second.getOrder());
        assertEquals(500, order.getTotalPrice());
        assertFalse(order.getOrderedAt().isBefore(before));
        assertEquals(order.getOrderedAt(), order.getUpdatedAt());
        assertEquals(0L, order.getVersion());
    }

    @Test
    void customerIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create(null, List.of(OrderItem.create(apple(), 1))));
    }

    @Test
    void zeroLinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), null));
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), Arrays.asList((OrderItem) null)));
    }

    @Test
    void itemCannotBeSharedBetweenOrders() {
        OrderItem item = OrderItem.create(apple(), 2);
        Order first = Order.create(customer(), List.of(item));
        assertThrows(IllegalArgumentException.class, () -> Order.create(customer(), List.of(item)));
        assertSame(first, item.getOrder());
        assertEquals(200, first.getTotalPrice());
    }

    @Test
    void collectionAndPublicApiDoNotAllowTotalOrSnapshotsToBeReplaced() {
        Order order = Order.create(customer(), List.of(OrderItem.create(apple(), 2)));
        assertThrows(UnsupportedOperationException.class, () -> order.getOrderItems().clear());
        assertFalse(Arrays.stream(Order.class.getMethods()).anyMatch(method -> method.getName().startsWith("set")));
        assertFalse(Arrays.stream(OrderItem.class.getMethods()).anyMatch(method -> method.getName().startsWith("set")));
        assertTrue(Arrays.stream(Order.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    }

    @Test
    void snapshotAndTotalDoNotFollowProductEdits() {
        Product product = apple();
        OrderItem item = OrderItem.create(product, 2);
        Order order = Order.create(customer(), List.of(item));
        product.setName("renamed");
        product.setPrice(900);
        assertEquals("apple", item.getProductNameSnapshot());
        assertEquals(100, item.getUnitPrice());
        assertEquals(200, item.getLineTotal());
        assertEquals(200, order.getTotalPrice());
    }

    @Test
    void lineMultiplicationOverflowIsRejected() {
        assertThrows(ArithmeticException.class, () -> Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", Integer.MAX_VALUE, 10), 2))));
    }

    @Test
    void sumOverflowIsRejectedEvenIfIndividualLinesFit() {
        assertThrows(ArithmeticException.class, () -> Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", Integer.MAX_VALUE, 10), 1),
                OrderItem.create(new Product(2L, "orange", 1, 10), 1))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void invalidSnapshotPriceIsRejected(Integer price) {
        assertThrows(IllegalArgumentException.class,
                () -> OrderItem.create(new Product(1L, "apple", price, 10), 1));
    }
}
