package com.example.demo.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class IdempotencyKeyTest {
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", " a", "a ", "a\n", "\t", "日本語", "a/b"})
    void invalidOpaqueKeysAreRejected(String key) {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.validate(key));
        assertThrows(IllegalArgumentException.class, () -> OrderIdempotency.validateKey(key));
    }

    @ParameterizedTest @ValueSource(strings = {"a", "A", "KeyABC", "keyabc", "A._:-09"})
    void validKeysRemainCaseSensitiveAndUnmodified(String key) {
        assertDoesNotThrow(() -> IdempotencyKey.validate(key));
        assertDoesNotThrow(() -> OrderIdempotency.validate(key, "a".repeat(64)));
    }

    @org.junit.jupiter.api.Test void exactLengthBoundary() {
        assertDoesNotThrow(() -> IdempotencyKey.validate("a".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.validate("a".repeat(129)));
    }
}
