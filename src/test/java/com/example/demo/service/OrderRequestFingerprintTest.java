package com.example.demo.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderRequestFingerprintTest {
    @Test
    void canonicalUtf8VectorHasExpectedLowercaseSha256() {
        String hash = OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 2, 2L, 3));
        // UTF-8の「1:2\n2:3」、末尾改行なしの既知vector。
        assertEquals("163affc78005025f8d4a3f1856d60e8b958dc87fc8b4ec9750425806d10eb4eb", hash);
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void reversedInsertionOrderAndRepeatedCallsHaveTheSameFingerprint() {
        Map<Long, Integer> reversed = new LinkedHashMap<>();
        reversed.put(2L, 3); reversed.put(1L, 2);
        String hash = OrderRequestFingerprint.fromValidatedItems(reversed);
        assertEquals(OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 2, 2L, 3)), hash);
        assertEquals(hash, OrderRequestFingerprint.fromValidatedItems(reversed));
    }

    @Test
    void changedQuantityOrProductChangesFingerprintWithoutAmbiguousConcatenation() {
        String hash = OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 2));
        assertNotEquals(hash, OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 3)));
        assertNotEquals(hash, OrderRequestFingerprint.fromValidatedItems(Map.of(2L, 2)));
        assertNotEquals(OrderRequestFingerprint.fromValidatedItems(Map.of(1L, 23)),
                OrderRequestFingerprint.fromValidatedItems(Map.of(12L, 3)));
    }
}
