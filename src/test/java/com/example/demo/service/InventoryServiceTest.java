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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
    @Mock private ProductRepository products;
    @Mock private InventoryMovementRepository movements;
    @Mock private CurrentUser currentUser;
    @Mock private EntityManager entityManager;
    @Spy private Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:02:03.123456789Z"), ZoneOffset.UTC);
    @InjectMocks private InventoryService service;
    private User admin() {
        User user = new User(2L, "admin", "private-hash", "ROLE_ADMIN");
        when(currentUser.requireUser()).thenReturn(user); return user;
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
        var response = service.adjust(1L, value);
        assertEquals(13, product.getStock()); assertEquals(3, response.stockBefore());
        assertEquals(13, response.stockAfter()); assertEquals(10, response.delta());
        assertEquals(actor.getId(), response.actor().id()); assertEquals("admin", response.actor().username());
        assertEquals(InventorySourceType.MANUAL, response.sourceType()); assertNull(response.sourceId());
        assertEquals("入荷", response.note());
        assertEquals(Instant.parse("2026-10-10T01:02:03.123456Z"), response.createdAt());
        var sequence = inOrder(products, entityManager, movements);
        sequence.verify(products).findByIdForUpdate(1L);
        sequence.verify(entityManager).refresh(product, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(products).save(product); sequence.verify(movements).save(any());
    }
    @ParameterizedTest @NullSource @ValueSource(ints = {0})
    void invalidDeltaFailsBeforeProductAccess(Integer delta) {
        admin();
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, request(delta)));
        verifyNoInteractions(products, movements, entityManager);
    }
    @Test
    void malformedServiceRequestsAreRejectedWithoutWrites() {
        admin();
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, null));
        var value = request(1); value.setReason(null);
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, value));
        value.setReason(ManualInventoryReason.CORRECTION); value.setNote("a".repeat(501));
        assertThrows(InvalidInventoryAdjustmentException.class, () -> service.adjust(1L, value));
        verifyNoInteractions(products, movements, entityManager);
    }
    @ParameterizedTest @CsvSource({"3,-5", "2147483647,1"})
    void impossibleResultDoesNotSaveEitherRepository(int stock, int delta) {
        admin(); var product = new Product(1L, "apple", 100L, stock);
        when(products.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        assertThrows(InventoryAdjustmentConflictException.class, () -> service.adjust(1L, request(delta)));
        assertEquals(stock, product.getStock()); verify(products, never()).save(any()); verifyNoInteractions(movements);
    }
    @Test
    void userIsRejectedBeforeLookupAndMissingProductIs404ForAdmin() {
        when(currentUser.requireUser()).thenReturn(com.example.demo.TestOrders.customer());
        assertThrows(AccessDeniedException.class, () -> service.adjust(999L, request(1)));
        assertThrows(AccessDeniedException.class, () -> service.history(999L, PageRequest.of(0, 20)));
        verifyNoInteractions(products, movements); admin();
        assertThrows(ProductNotFoundException.class, () -> service.adjust(999L, request(1)));
        assertThrows(ProductNotFoundException.class, () -> service.history(999L, PageRequest.of(0, 20)));
        verifyNoInteractions(movements);
    }
    @Test
    void historyUsesPageSizeAndStableServerSort() {
        admin(); when(products.existsById(1L)).thenReturn(true);
        Pageable expected = PageRequest.of(2, 7, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(movements.findByProductId(1L, expected)).thenReturn(Page.empty(expected));
        assertEquals(0, service.history(1L, PageRequest.of(2, 7, Sort.by("delta"))).getTotalElements());
        verify(movements).findByProductId(1L, expected);
    }
}
