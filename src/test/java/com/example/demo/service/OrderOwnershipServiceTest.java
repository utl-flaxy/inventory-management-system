package com.example.demo.service;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.example.demo.TestOrders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderOwnershipServiceTest {
    @Mock
    private OrderRepository orders;
    @Mock
    private ProductRepository products;
    @Mock
    private CurrentUser currentUser;
    @Mock
    private EntityManager entityManager;
    @InjectMocks
    private OrderService service;

    @BeforeEach
    void authenticatedCustomer() {
        when(currentUser.requireUser()).thenReturn(customer());
    }

    @Test
    void ownListUsesOnlyTheCustomerScopedQuery() {
        Order order = order(101L, OrderStatus.PENDING, OrderItem.create(new Product(1L, "apple", 100, 8), 2));
        when(orders.findAllByCustomerId(1L)).thenReturn(List.of(order));
        assertEquals(List.of(order), service.findMyOrders());
        verify(orders).findAllByCustomerId(1L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @Test
    void ownDetailUsesOnlyTheIdAndCustomerQuery() {
        Order order = order(101L, OrderStatus.PENDING, OrderItem.create(new Product(1L, "apple", 100, 8), 2));
        when(orders.findByIdAndCustomerId(101L, 1L)).thenReturn(Optional.of(order));
        assertSame(order, service.findMyOrder(101L));
        verify(orders).findByIdAndCustomerId(101L, 1L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 999})
    void foreignAndMissingDetailsDoNotFallBackToAnUnscopedLookup(long id) {
        when(orders.findByIdAndCustomerId(id, 1L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.findMyOrder(id));
        verify(orders).findByIdAndCustomerId(id, 1L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 999})
    void foreignAndMissingCancellationDoNotLockOrRestoreAnyInventory(long id) {
        when(orders.findByIdAndCustomerIdForUpdate(id, 1L)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> service.cancelMyOrder(id));
        verify(orders).findByIdAndCustomerIdForUpdate(id, 1L);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @Test
    void ownerCancellationLocksWithOwnershipAndRestoresOnlyOnce() {
        Product product = new Product(1L, "apple", 100, 8);
        Order order = order(101L, OrderStatus.PENDING, OrderItem.create(product, 2));
        when(orders.findByIdAndCustomerIdForUpdate(101L, 1L)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);

        assertSame(order, service.cancelMyOrder(101L));
        var sequence = inOrder(orders, entityManager, products);
        sequence.verify(orders).findByIdAndCustomerIdForUpdate(101L, 1L);
        sequence.verify(entityManager).refresh(order, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(entityManager).refresh(product, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(products).save(product);
        sequence.verify(orders).save(order);
        assertEquals(10, product.getStock());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertThrows(IllegalOrderStateTransitionException.class, () -> service.cancelMyOrder(101L));
        assertEquals(10, product.getStock());
        verify(products, times(1)).save(product);
        verify(orders, never()).findById(any());
        verify(orders, never()).findByIdForUpdate(any());
        verify(orders, never()).findByIdAndCustomerId(any(), any());
    }
}
