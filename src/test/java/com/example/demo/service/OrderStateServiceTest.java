package com.example.demo.service;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.OrderInventoryRestorationException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStateServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private EntityManager entityManager;
    @InjectMocks
    private OrderService orderService;

    private Order order(OrderStatus status, Product product, int quantity) {
        Order order = new Order(1L, 500, status, List.of());
        order.setOrderItems(List.of(new OrderItem(null, quantity, 100, order, product)));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        return order;
    }

    private Order command(OrderStatus target) {
        return switch (target) {
            case SHIPPED -> orderService.shipOrder(1L);
            case COMPLETED -> orderService.completeOrder(1L);
            case CANCELLED -> orderService.cancelOrder(1L);
            case PENDING -> orderService.updateStatus(1L, target);
        };
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,PENDING,false", "PENDING,SHIPPED,true", "PENDING,COMPLETED,false", "PENDING,CANCELLED,true",
            "SHIPPED,PENDING,false", "SHIPPED,SHIPPED,false", "SHIPPED,COMPLETED,true", "SHIPPED,CANCELLED,false",
            "COMPLETED,PENDING,false", "COMPLETED,SHIPPED,false", "COMPLETED,COMPLETED,false", "COMPLETED,CANCELLED,false",
            "CANCELLED,PENDING,false", "CANCELLED,SHIPPED,false", "CANCELLED,COMPLETED,false", "CANCELLED,CANCELLED,false"
    })
    void commandsPreserveStockExceptSuccessfulCancellation(OrderStatus from, OrderStatus to, boolean allowed) {
        Product product = new Product(1L, "apple", 100, 5);
        Order order = order(from, product, 5);

        if (allowed) {
            when(orderRepository.save(order)).thenReturn(order);
            assertSame(order, command(to));
            assertEquals(to, order.getStatus());
            assertEquals(to == OrderStatus.CANCELLED ? 10 : 5, product.getStock());
            verify(orderRepository).save(order);
            if (to == OrderStatus.CANCELLED) {
                verify(productRepository).save(product);
            } else {
                verifyNoInteractions(productRepository);
            }
        } else {
            assertThrows(IllegalOrderStateTransitionException.class, () -> command(to));
            assertEquals(from, order.getStatus());
            assertEquals(5, product.getStock());
            verify(orderRepository, never()).save(any());
            verifyNoInteractions(productRepository);
        }
        verifyNoInteractions(orderItemRepository);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "COMPLETED", "CANCELLED"})
    void legacyStatusApiUsesTheSameCommands(OrderStatus target) {
        Product product = new Product(1L, "apple", 100, 5);
        Order order = order(target == OrderStatus.COMPLETED ? OrderStatus.SHIPPED : OrderStatus.PENDING, product, 5);
        when(orderRepository.save(order)).thenReturn(order);

        assertSame(order, orderService.updateStatus(1L, target));

        assertEquals(target, order.getStatus());
        assertEquals(target == OrderStatus.CANCELLED ? 10 : 5, product.getStock());
    }

    @Test
    void cancelTwiceAndReturningToPendingNeverIncreaseStockAgain() {
        Product product = new Product(1L, "apple", 100, 5);
        Order order = order(OrderStatus.PENDING, product, 5);
        when(orderRepository.save(order)).thenReturn(order);

        orderService.cancelOrder(1L);
        assertEquals(10, product.getStock());
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.updateStatus(1L, OrderStatus.PENDING));
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.cancelOrder(1L));
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.updateStatus(1L, OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(10, product.getStock());
        verify(productRepository, times(1)).save(product);
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void nullStatusDoesNotReadOrWriteRepositories() {
        assertThrows(InvalidOrderRequestException.class, () -> orderService.updateStatus(1L, null));
        verifyNoInteractions(orderRepository, productRepository, orderItemRepository);
    }

    @Test
    void restorationOverflowFailsBeforeChangingAnyProductOrStatus() {
        Product first = new Product(1L, "apple", 100, 5);
        Product second = new Product(2L, "orange", 100, Integer.MAX_VALUE);
        Order order = order(OrderStatus.PENDING, first, 5);
        order.setOrderItems(List.of(new OrderItem(null, 5, 100, order, first), new OrderItem(null, 1, 100, order, second)));

        assertThrows(OrderInventoryRestorationException.class, () -> orderService.cancelOrder(1L));

        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertEquals(5, first.getStock());
        assertEquals(Integer.MAX_VALUE, second.getStock());
        verifyNoInteractions(productRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void repeatedProductLinesAreAccumulatedBeforeCheckingOverflow() {
        Product product = new Product(1L, "apple", 100, Integer.MAX_VALUE - 1);
        Order order = order(OrderStatus.PENDING, product, 1);
        order.setOrderItems(List.of(new OrderItem(null, 1, 100, order, product), new OrderItem(null, 1, 100, order, product)));

        assertThrows(OrderInventoryRestorationException.class, () -> orderService.cancelOrder(1L));

        assertEquals(Integer.MAX_VALUE - 1, product.getStock());
        assertEquals(OrderStatus.PENDING, order.getStatus());
        verifyNoInteractions(productRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void restorationAtIntegerLimitSucceeds() {
        Product product = new Product(1L, "apple", 100, Integer.MAX_VALUE - 1);
        Order order = order(OrderStatus.PENDING, product, 1);
        when(orderRepository.save(order)).thenReturn(order);

        orderService.cancelOrder(1L);

        assertEquals(Integer.MAX_VALUE, product.getStock());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void missingOrderIsNotFound(OrderStatus target) {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> command(target));

        verifyNoInteractions(productRepository, orderItemRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancellationLocksOrderThenUniqueProductsInAscendingIdOrder() {
        Product first = new Product(1L, "apple", 100, 5);
        Product second = new Product(2L, "orange", 100, 7);
        Order order = order(OrderStatus.PENDING, first, 2);
        order.setOrderItems(List.of(new OrderItem(null, 3, 100, order, second),
                new OrderItem(null, 2, 100, order, first), new OrderItem(null, 1, 100, order, second)));

        orderService.cancelOrder(1L);

        var locks = inOrder(orderRepository, entityManager, productRepository);
        locks.verify(orderRepository).findByIdForUpdate(1L);
        locks.verify(entityManager).refresh(order, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entityManager).refresh(first, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entityManager).refresh(second, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(productRepository).save(first);
        locks.verify(productRepository).save(second);
        verify(entityManager, times(1)).refresh(second, LockModeType.PESSIMISTIC_WRITE);
        assertEquals(7, first.getStock());
        assertEquals(11, second.getStock());
    }
}
