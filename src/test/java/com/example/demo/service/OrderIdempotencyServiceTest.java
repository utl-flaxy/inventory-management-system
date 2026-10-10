package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.*;
import com.example.demo.exception.*;
import com.example.demo.repository.*;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.demo.TestOrders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {
    @Mock private ProductRepository products;
    @Mock private OrderRepository orders;
    @Mock private UserRepository users;
    @Mock private CurrentUser currentUser;
    @Mock private EntityManager entityManager;
    @InjectMocks private OrderService service;

    private User lockedCustomer() {
        User user = customer();
        when(currentUser.requireUser()).thenReturn(user);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        return user;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " leading", "trailing ", "line\nbreak", "carriage\rreturn",
            "control\u0001", "tab\t", "slash/key", "日本語", "key,key"})
    void invalidKeysFailBeforeAuthenticationOrRepositories(String key) {
        assertThrows(InvalidIdempotencyKeyException.class, () -> service.createOrder(request(1L, 3), key));
        verifyNoInteractions(currentUser, users, orders, products, entityManager);
    }

    @Test
    void keyLongerThan128CharactersIsRejectedWithoutTrimmingOrDatabaseWork() {
        assertThrows(InvalidIdempotencyKeyException.class, () -> service.createOrder(request(1L, 3), "a".repeat(129)));
        verifyNoInteractions(currentUser, users, orders, products, entityManager);
    }

    @Test
    void replayReturnsCurrentOrderWithoutProductReadLockOrSave() {
        User user = lockedCustomer();
        Order existing = Order.create(user, List.of(OrderItem.create(new Product(1L, "apple", 100L, 7), 3)),
                "same-key", OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 3)));
        existing.ship();
        when(orders.findByCustomerIdAndIdempotencyKey(1L, "same-key")).thenReturn(Optional.of(existing));
        assertSame(existing, service.createOrder(request(1L, 3), "same-key"));
        assertEquals(OrderStatus.SHIPPED, existing.getStatus());
        verify(users).findByIdForUpdate(1L);
        verify(orders).findByCustomerIdAndIdempotencyKey(1L, "same-key");
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @Test
    void differentBodyConflictsBeforeAnyProductAccessOrOrderWrite() {
        User user = lockedCustomer();
        Order existing = Order.create(user, List.of(OrderItem.create(new Product(1L, "apple", 100L, 7), 3)),
                "same-key", OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 3)));
        when(orders.findByCustomerIdAndIdempotencyKey(1L, "same-key")).thenReturn(Optional.of(existing));
        assertThrows(IdempotencyConflictException.class, () -> service.createOrder(request(1L, 2), "same-key"));
        verify(orders).findByCustomerIdAndIdempotencyKey(1L, "same-key");
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(products, entityManager);
    }

    @Test
    void creationLocksCustomerAndLooksUpScopedKeyBeforeProductsAndPersistsIdentity() {
        User user = lockedCustomer();
        Product product = new Product(1L, "apple", 100L, 10);
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Order result = service.createOrder(request(1L, 3), "A._:-09");
        var sequence = inOrder(currentUser, users, orders, products, entityManager);
        sequence.verify(currentUser).requireUser();
        sequence.verify(users).findByIdForUpdate(user.getId());
        sequence.verify(orders).findByCustomerIdAndIdempotencyKey(user.getId(), "A._:-09");
        sequence.verify(products).findByIdForUpdate(1L);
        sequence.verify(entityManager).refresh(product, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(products).save(product);
        sequence.verify(orders).save(result);
        assertEquals("A._:-09", result.getIdempotencyKey());
        assertEquals(OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 3)), result.getRequestFingerprint());
        assertEquals(7, product.getStock());
    }

    @Test
    void duplicateProductsAreRejectedBeforeFingerprintCanMergeThem() {
        OrderRequest duplicate = request(new com.example.demo.dto.OrderItemRequest(1L, 1),
                new com.example.demo.dto.OrderItemRequest(1L, 2));
        assertThrows(InvalidOrderRequestException.class, () -> service.createOrder(duplicate, "key"));
        verifyNoInteractions(currentUser, users, orders, products, entityManager);
    }

    @ParameterizedTest
    @ValueSource(ints = {1062, 1048})
    void unrelatedSqlConstraintsAreNotClassifiedAsIdempotencyConflicts(int code) {
        String message = code == 1062 ? "Duplicate entry for key other_unique"
                : "Invalid null uk_orders_customer_idempotency_key";
        assertUnrelatedFailure(new DataIntegrityViolationException("unrelated",
                new SQLException(message, "23000", code)));
    }

    @Test
    void NonSqlIntegrityFailureIsNotReplayedOrConvertedToConflict() {
        assertUnrelatedFailure(new DataIntegrityViolationException("unrelated"));
    }

    private void assertUnrelatedFailure(DataIntegrityViolationException failure) {
        lockedCustomer();
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        when(orders.save(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createOrder(request(1L, 2), "key")));
        // catch内でrollback-only Transactionの再照会を行わない。
        verify(orders).findByCustomerIdAndIdempotencyKey(1L, "key");
        verify(orders).save(any());
        verifyNoMoreInteractions(orders);
    }
}
