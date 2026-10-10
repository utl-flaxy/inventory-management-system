package com.example.demo.entity;

import com.example.demo.dto.InventoryMovementResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAdjustmentIdempotencyTest {
    private final Product product = new Product(1L, "apple", 100L, 10);
    private final User actor = new User(2L, "admin", "private-hash", "ROLE_ADMIN");
    private final Instant time = Instant.parse("2026-10-10T01:02:03Z");
    private InventoryMovement manual(String key, String hash) {
        return InventoryMovement.createManual(product, 1, 10, 11, InventoryMovementReason.RECEIPT, actor, null, time, key, hash);
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "g000000000000000000000000000000000000000000000000000000000000000"})
    void manualFactoryRequiresLowercaseFingerprint(String fingerprint) {
        assertThrows(IllegalArgumentException.class, () -> manual("key", fingerprint));
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "invalid key"})
    void manualFactoryRequiresOpaqueKey(String key) {
        assertThrows(IllegalArgumentException.class, () -> manual(key, "a".repeat(64)));
    }
    @Test void keyedManualMetadataIsImmutableAndHiddenFromResponse() throws Exception {
        var movement = manual("KeyABC", "a".repeat(64));
        assertEquals("KeyABC", movement.getIdempotencyKey());
        assertEquals("a".repeat(64), movement.getRequestFingerprint());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(InventoryMovementResponse.from(movement));
        for (String name : new String[]{"idempotencyKey", "requestFingerprint", "password", "hash"})
            assertFalse(json.toString().contains(name));
        for (var method : InventoryMovement.class.getMethods()) assertFalse(method.getName().startsWith("set"));
    }
    @Test void legacyManualAndOrderFactoriesKeepNullMetadata() {
        var legacy = InventoryMovement.create(product, 1, 10, 11, InventoryMovementReason.RECEIPT,
                InventorySourceType.MANUAL, null, actor, null, time);
        var order = InventoryMovement.create(product, -1, 10, 9, InventoryMovementReason.ORDER_CREATED,
                InventorySourceType.ORDER, 1L, actor, null, time);
        assertNull(legacy.getIdempotencyKey()); assertNull(legacy.getRequestFingerprint());
        assertNull(order.getIdempotencyKey()); assertNull(order.getRequestFingerprint());
    }
}
