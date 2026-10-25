package com.example.demo.service;

import com.example.demo.dto.*;
import com.example.demo.entity.*;
import com.example.demo.exception.*;
import com.example.demo.repository.*;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.Optional;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
    @Mock private ProductRepository products;
    @Mock private InventoryMovementRepository movements;
    @Mock private UserRepository users;
    @Mock private CurrentUser currentUser;
    @Mock private EntityManager entityManager;
    @Spy private Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:02:03.123456789Z"), ZoneOffset.UTC);
    @InjectMocks private InventoryService service;
    private User admin() {
        User user = new User(2L, "admin", "private-hash", "ROLE_ADMIN");
        when(currentUser.requireUser()).thenReturn(user);
        lenient().when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        return user;
    }
    private InventoryAdjustmentRequest request(Integer delta) {
        var value = new InventoryAdjustmentRequest();
        value.setDelta(delta); value.setReason(ManualInventoryReason.RECEIPT); return value;
    }
    @Test
    void locksRefreshesAndSavesStockAndHistoryWithActualActor() {
        User actor = admin(); Product product = new Product(1L, "apple", 100L, 3);
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(movements.save(any())).thenAnswer(call -> call.getArgument(0));
        var value = request(10); value.setNote("入荷");
        var response = service.adjust(1L, value, "key");
        assertEquals(13, product.getStock()); assertEquals(3, response.stockBefore());
        assertEquals(13, response.stockAfter()); assertEquals(10, response.delta());
        assertEquals(actor.getId(), response.actor().id()); assertEquals("admin", response.actor().username());
        assertEquals(InventorySourceType.MANUAL, response.sourceType()); assertNull(response.sourceId());
        assertEquals("入荷", response.note());
        assertEquals(Instant.parse("2026-10-10T01:02:03.123456Z"), response.createdAt());
        var sequence = inOrder(users, products, entityManager, movements);
        sequence.verify(users).findByIdForUpdate(actor.getId());
        sequence.verify(movements).findByActorIdAndIdempotencyKey(actor.getId(), "key");
        sequence.verify(products).findByIdForUpdate(1L);
        sequence.verify(entityManager).refresh(product, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(products).save(product); sequence.verify(movements).save(any());
        sequence.verify(products).flush();
    }
    @ParameterizedTest @NullSource @ValueSource(ints = {0})
    void invalidDeltaFailsBeforeProductAccess(Integer delta) {
        admin();
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, request(delta), "key"));
        verifyNoInteractions(products, movements, entityManager);
    }
    @Test
    void malformedServiceRequestsAreRejectedWithoutWrites() {
        admin();
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, null, "key"));
        var value = request(1); value.setReason(null);
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, value, "key"));
        value.setReason(ManualInventoryReason.CORRECTION); value.setNote("a".repeat(501));
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, value, "key"));
        verifyNoInteractions(products, movements, entityManager);
    }
    @ParameterizedTest @CsvSource({"3,-5", "2147483647,1"})
    void impossibleResultDoesNotSaveEitherRepository(int stock, int delta) {
        admin(); var product = new Product(1L, "apple", 100L, stock);
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        assertThrows(InventoryAdjustmentConflictException.class, () -> service.adjust(1L, request(delta), "key"));
        assertEquals(stock, product.getStock()); verify(products, never()).save(any());
        verify(movements).findByActorIdAndIdempotencyKey(2L, "key"); verifyNoMoreInteractions(movements);
    }
    @Test
    void userIsRejectedBeforeLookupAndMissingProductIs404ForAdmin() {
        when(currentUser.requireUser()).thenReturn(com.example.demo.TestOrders.customer());
        assertThrows(AccessDeniedException.class, () -> service.adjust(999L, request(1), "key"));
        assertThrows(AccessDeniedException.class, () -> service.history(999L, PageRequest.of(0, 20)));
        verifyNoInteractions(products, movements); admin();
        assertThrows(ProductNotFoundException.class, () -> service.adjust(999L, request(1), "key"));
        assertThrows(ProductNotFoundException.class, () -> service.history(999L, PageRequest.of(0, 20)));
        verify(movements).findByActorIdAndIdempotencyKey(2L, "key"); verifyNoMoreInteractions(movements);
    }
    @Test
    void historyUsesPageSizeAndStableServerSort() {
        admin(); when(products.existsById(1L)).thenReturn(true);
        Pageable expected = PageRequest.of(2, 7, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(movements.findByProductId(1L, expected)).thenReturn(Page.empty(expected));
        assertEquals(0, service.history(1L, PageRequest.of(2, 7, Sort.by("delta"))).getTotalElements());
        verify(movements).findByProductId(1L, expected);
    }

    @ParameterizedTest
    @CsvSource({"2147483647,2", "2147483647,20", "1073741824,2", "107374183,20"})
    void historyRejectsUnrepresentableOffsetBeforeMovementQuery(int page, int size) {
        admin(); when(products.existsById(1L)).thenReturn(true);
        assertThrows(InvalidInventoryPageRequestException.class,
                () -> service.history(1L, PageRequest.of(page, size)));
        verifyNoInteractions(movements, users, entityManager);
    }

    @Test
    void historyAcceptsAnOffsetExactlyAtIntegerMaxValue() {
        admin(); when(products.existsById(1L)).thenReturn(true);
        var expected = PageRequest.of(Integer.MAX_VALUE, 1, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(movements.findByProductId(1L, expected)).thenReturn(Page.empty(expected));
        assertEquals(expected, service.history(1L, PageRequest.of(Integer.MAX_VALUE, 1)).getPageable());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "invalid key"})
    void invalidKeyFailsBeforeActorLockOrRepositories(String key) {
        admin();
        assertThrows(InvalidIdempotencyKeyException.class, () -> service.adjust(1L, request(1), key));
        verifyNoInteractions(users, products, movements, entityManager);
    }

    @Test void requestDefensePrecedesInvalidKeyAndActorLock() {
        admin();
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, request(0), null));
        verifyNoInteractions(users, products, movements, entityManager);
    }

    private InventoryMovement existing(User actor) {
        var original = request(1);
        return InventoryMovement.createManual(new Product(1L, "apple", 100L, 200), 1, 10, 11,
                InventoryMovementReason.RECEIPT, actor, null, clock.instant(), "key",
                InventoryAdjustmentFingerprint.fromValidatedRequest(1L, original));
    }

    @Test void replayReturnsHistoricalStocksWithoutProductLookupSaveOrClockRecalculation() {
        User actor = admin(); var existing = existing(actor);
        when(movements.findByActorIdAndIdempotencyKey(2L, "key")).thenReturn(Optional.of(existing));
        var response = service.adjust(1L, request(1), "key");
        assertEquals(10, response.stockBefore()); assertEquals(11, response.stockAfter());
        verify(users).findByIdForUpdate(2L);
        verify(movements).findByActorIdAndIdempotencyKey(2L, "key");
        verifyNoMoreInteractions(movements); verifyNoInteractions(products, entityManager);
    }

    @ParameterizedTest @ValueSource(strings = {"product", "delta", "reason", "note"})
    void mismatchesFailBeforeProductLookupOrMutation(String field) {
        User actor = admin();
        InventoryMovement original = existing(actor);
        when(movements.findByActorIdAndIdempotencyKey(2L, "key")).thenReturn(Optional.of(original));
        var changed = request(field.equals("delta") ? 2 : 1);
        if (field.equals("reason")) changed.setReason(ManualInventoryReason.DAMAGE);
        if (field.equals("note")) changed.setNote("入荷");
        assertThrows(IdempotencyConflictException.class,
                () -> service.adjust(field.equals("product") ? 2L : 1L, changed, "key"));
        verifyNoInteractions(products, entityManager); verify(movements, never()).save(any());
    }

    @ParameterizedTest @ValueSource(strings = {"save", "flush"})
    void onlyKnownDuplicateKeyBecomesConflictAndIsNotRequeriedAfterFailure(String phase) {
        admin(); when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        var failure = new DataIntegrityViolationException("SQL private", new SQLException(
                "Duplicate entry '2-key' for key 'uk_inventory_movements_actor_idempotency_key'", "23000", 1062));
        if (phase.equals("save")) doThrow(failure).when(movements).save(any());
        else doThrow(failure).when(products).flush();
        assertThrows(IdempotencyConflictException.class, () -> service.adjust(1L, request(1), "key"));
        verify(movements, times(1)).findByActorIdAndIdempotencyKey(2L, "key");
    }

    static Stream<Arguments> unrelatedConstraints() {
        return Stream.of(Arguments.of(1062, "uk_users_username"), Arguments.of(1451, "uk_inventory_movements_actor_idempotency_key"),
                Arguments.of(1062, "uk_inventory_movements_actor_idempotency_key_suffix"),
                Arguments.of(1062, "prefix_uk_inventory_movements_actor_idempotency_key"));
    }
    @ParameterizedTest @MethodSource("unrelatedConstraints")
    void unrelatedConstraintsAreRethrownUnchanged(int code, String constraint) {
        admin(); when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        var failure = new DataIntegrityViolationException("SQL private", new SQLException(
                "Duplicate entry '2-key' for key '" + constraint + "'", "23000", code));
        doThrow(failure).when(movements).save(any());
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.adjust(1L, request(1), "key")));
    }
}
