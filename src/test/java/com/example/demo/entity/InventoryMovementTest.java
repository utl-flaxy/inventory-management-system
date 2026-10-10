package com.example.demo.entity;

import com.example.demo.exception.InventoryAdjustmentConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMovementTest {
    private final Product product = new Product(1L, "apple", 100L, 10);
    private final User actor = com.example.demo.TestOrders.customer();
    private final Instant time = Instant.parse("2026-10-10T01:02:03.123456789Z");

    @ParameterizedTest @CsvSource({"3,13", "-3,7", "-10,0"})
    void signedAdjustmentChangesOnlyByDelta(int delta, int after) {
        product.adjustStock(delta);
        assertEquals(after, product.getStock());
    }
    @ParameterizedTest @NullSource @ValueSource(ints = {0})
    void invalidDeltaDoesNotChangeStock(Integer delta) {
        assertThrows(IllegalArgumentException.class, () -> product.adjustStock(delta));
        assertEquals(10, product.getStock());
    }
    @ParameterizedTest @CsvSource({"3,-5", "2147483647,1", "3,-2147483648"})
    void conflictPreservesStockAndHidesArithmetic(int before, int delta) {
        Product value = new Product(1L, "apple", 100L, before);
        var failure = assertThrows(InventoryAdjustmentConflictException.class, () -> value.adjustStock(delta));
        assertEquals(before, value.getStock());
        assertNull(failure.getCause());
    }
    @ParameterizedTest @EnumSource(InventoryMovementReason.class)
    void reasonsRequireCorrectSourceActorAndSignedChain(InventoryMovementReason reason) {
        int delta = reason == InventoryMovementReason.ORDER_CREATED ? -3 : 3;
        var value = InventoryMovement.create(product, delta, 10, 10 + delta, reason,
                reason.isManual() ? InventorySourceType.MANUAL : InventorySourceType.ORDER,
                reason.isManual() ? null : 5L, actor, null, time);
        assertEquals(10, value.getStockBefore());
        assertEquals(10 + delta, value.getStockAfter());
        assertSame(actor, value.getActor());
        assertEquals(Instant.parse("2026-10-10T01:02:03.123456Z"), value.getCreatedAt());
    }
    static Stream<Arguments> invalidMovements() {
        return Stream.of(
            Arguments.of(0, 10, 10, InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, null),
            Arguments.of(1, -1, 0, InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, null),
            Arguments.of(-1, 0, -1, InventoryMovementReason.DAMAGE, InventorySourceType.MANUAL, null, null),
            Arguments.of(3, 10, 12, InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, null),
            Arguments.of(1, Integer.MAX_VALUE, 0, InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, null),
            Arguments.of(-3, 10, 7, InventoryMovementReason.ORDER_CREATED, InventorySourceType.MANUAL, null, null),
            Arguments.of(-3, 10, 7, InventoryMovementReason.ORDER_CREATED, InventorySourceType.ORDER, null, null),
            Arguments.of(-3, 10, 7, InventoryMovementReason.ORDER_CREATED, InventorySourceType.ORDER, -1L, null),
            Arguments.of(3, 10, 13, InventoryMovementReason.RECEIPT, InventorySourceType.ORDER, 5L, null),
            Arguments.of(3, 10, 13, InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, 5L, null),
            Arguments.of(3, 10, 13, InventoryMovementReason.ORDER_CREATED, InventorySourceType.ORDER, 5L, null),
            Arguments.of(-3, 10, 7, InventoryMovementReason.ORDER_CANCELLED, InventorySourceType.ORDER, 5L, null),
            Arguments.of(-3, 10, 7, InventoryMovementReason.ORDER_CREATED, InventorySourceType.ORDER, 5L, "note"),
            Arguments.of(3, 10, 13, null, InventorySourceType.MANUAL, null, null),
            Arguments.of(3, 10, 13, InventoryMovementReason.RECEIPT, null, null, null));
    }
    @ParameterizedTest @MethodSource("invalidMovements")
    void invalidAuditRecordIsRejected(Integer delta, Integer before, Integer after, InventoryMovementReason reason,
            InventorySourceType source, Long sourceId, String note) {
        assertThrows(IllegalArgumentException.class, () -> InventoryMovement.create(product, delta, before, after,
                reason, source, sourceId, actor, note, time));
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "　"})
    void blankNotesNormalizeToNull(String note) {
        assertNull(InventoryMovement.create(product, 1, 10, 11, InventoryMovementReason.RECEIPT,
                InventorySourceType.MANUAL, null, actor, note, time).getNote());
    }
    @Test
    void noteBoundaryAndRequiredActorTimeAreEnforced() {
        assertEquals(500, InventoryMovement.create(product, 1, 10, 11, InventoryMovementReason.CORRECTION,
                InventorySourceType.MANUAL, null, actor, "a".repeat(500), time).getNote().length());
        assertThrows(IllegalArgumentException.class, () -> InventoryMovement.create(product, 1, 10, 11,
                InventoryMovementReason.CORRECTION, InventorySourceType.MANUAL, null, actor, "a".repeat(501), time));
        assertThrows(IllegalArgumentException.class, () -> InventoryMovement.create(product, 1, 10, 11,
                InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, null, null, time));
        assertThrows(IllegalArgumentException.class, () -> InventoryMovement.create(product, 1, 10, 11,
                InventoryMovementReason.RECEIPT, InventorySourceType.MANUAL, null, actor, null, null));
    }
}
