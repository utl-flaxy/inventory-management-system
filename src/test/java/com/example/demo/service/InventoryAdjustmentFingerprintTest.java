package com.example.demo.service;

import com.example.demo.dto.InventoryAdjustmentRequest;
import com.example.demo.dto.ManualInventoryReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAdjustmentFingerprintTest {
    private InventoryAdjustmentRequest request(int delta, ManualInventoryReason reason, String note) {
        var request = new InventoryAdjustmentRequest();
        request.setDelta(delta); request.setReason(reason); request.setNote(note); return request;
    }
    @Test void utf8CanonicalVectorHasStableLowercaseSha256() {
        // inventory-adjustment:v1\nproductId:12\ndelta:-3\nreason:DAMAGE\nnote:utf8:6:破損
        assertEquals("f32f591bb2a1e5c5e6953c59e4bac59cd9d215149d53c3d2cd0356c75a351a96",
                InventoryAdjustmentFingerprint.fromValidatedRequest(12L, request(-3, ManualInventoryReason.DAMAGE, "破損")));
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "   ", "　", "\n\t"})
    void nullAndUnicodeBlankNotesHaveSameMeaning(String note) {
        assertNull(InventoryAdjustmentFingerprint.normalizeNote(note));
        assertEquals(InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, null)),
                InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, note)));
    }
    @Test void nonblankWhitespaceAndNullMarkerAreNotCollapsed() {
        String original = InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, "入荷"));
        for (String note : new String[]{" 入荷 ", "入荷\n", "null", "utf8:6:入荷"}) {
            assertEquals(note, InventoryAdjustmentFingerprint.normalizeNote(note));
            assertNotEquals(original, InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, note)));
        }
        assertNotEquals(InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, null)),
                InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, "null")));
    }
    @Test void everySemanticFieldChangesFingerprint() {
        var original = request(1, ManualInventoryReason.RECEIPT, "入荷");
        String expected = InventoryAdjustmentFingerprint.fromValidatedRequest(1L, original);
        assertNotEquals(expected, InventoryAdjustmentFingerprint.fromValidatedRequest(2L, original));
        assertNotEquals(expected, InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(2, ManualInventoryReason.RECEIPT, "入荷")));
        assertNotEquals(expected, InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.CORRECTION, "入荷")));
        assertNotEquals(expected, InventoryAdjustmentFingerprint.fromValidatedRequest(1L, request(1, ManualInventoryReason.RECEIPT, "入荷B")));
    }
}
