package com.example.demo.entity;

import java.util.regex.Pattern;

public final class OrderIdempotency {
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private OrderIdempotency() { }

    public static void validateKey(String key) {
        IdempotencyKey.validate(key);
    }

    public static void validate(String key, String fingerprint) {
        validateKey(key);
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("注文fingerprintはSHA-256の64文字lowercase hexが必須です");
        }
    }
}
