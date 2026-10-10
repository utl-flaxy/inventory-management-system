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
        return new Product(1L, "apple", 100L, 10);
    }

    @Test
    void creationSetsCustomerStateTimestampsVersionAndBothSidesOfEachLine() {
        Instant before = Instant.now();
        User customer = customer();
        OrderItem first = OrderItem.create(apple(), 2);
        OrderItem second = OrderItem.create(new Product(2L, "orange", 300L, 5), 1);
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
        product.setPrice(900L);
        assertEquals("apple", item.getProductNameSnapshot());
        assertEquals(100, item.getUnitPrice());
        assertEquals(200, item.getLineTotal());
        assertEquals(200, order.getTotalPrice());
    }

    @Test
    void lineMultiplicationOverflowIsRejected() {
        assertThrows(ArithmeticException.class, () -> Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", Long.MAX_VALUE, 10), 2))));
    }

    @Test
    void sumOverflowIsRejectedEvenIfIndividualLinesFit() {
        assertThrows(ArithmeticException.class, () -> Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", Long.MAX_VALUE, 10), 1),
                OrderItem.create(new Product(2L, "orange", 1L, 10), 1))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1})
    void invalidSnapshotPriceIsRejected(Long price) {
        assertThrows(IllegalArgumentException.class,
                () -> OrderItem.create(new Product(1L, "apple", price, 10), 1));
    }

    @Test
    void formerIntegerMultiplicationAndSumLimitsAreNowValid() {
        Order multiplied = Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", (long) Integer.MAX_VALUE, 10), 2)));
        assertEquals(4_294_967_294L, multiplied.getTotalPrice());
        Order added = Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", (long) Integer.MAX_VALUE, 10), 1),
                OrderItem.create(new Product(2L, "orange", 1L, 10), 1)));
        assertEquals(2_147_483_648L, added.getTotalPrice());
    }

    @Test
    void multipleHighValueLinesUseLongArithmetic() {
        Order order = Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", 3_000_000_000L, 10), 2),
                OrderItem.create(new Product(2L, "orange", 4_000_000_000L, 10), 3)));
        assertEquals(6_000_000_000L, order.getOrderItems().get(0).getLineTotal());
        assertEquals(12_000_000_000L, order.getOrderItems().get(1).getLineTotal());
        assertEquals(18_000_000_000L, order.getTotalPrice());
    }

    @Test
    void highValueSnapshotsRemainUnchangedAfterProductPriceEdits() {
        Product product = new Product(1L, "apple", 3_000_000_000L, 10);
        OrderItem item = OrderItem.create(product, 2);
        Order order = Order.create(customer(), List.of(item));
        product.setPrice(4_000_000_000L);
        assertEquals(3_000_000_000L, item.getUnitPrice());
        assertEquals(6_000_000_000L, item.getLineTotal());
        assertEquals(6_000_000_000L, order.getTotalPrice());
    }

    @Test
    void longMaximumCanBeRepresentedWithoutOverflow() {
        Order order = Order.create(customer(), List.of(
                OrderItem.create(new Product(1L, "apple", Long.MAX_VALUE, 1), 1)));
        assertEquals(Long.MAX_VALUE, order.getTotalPrice());
        assertEquals(Long.MAX_VALUE, order.getOrderItems().get(0).getLineTotal());
    }
}
