package com.example.demo.entity;

import java.util.regex.Pattern;

public final class OrderIdempotency {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private OrderIdempotency() { }

    public static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Idempotency-Keyは1〜128文字の英数字または._:-を指定してください");
        }
    }

    public static void validate(String key, String fingerprint) {
        validateKey(key);
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("注文fingerprintはSHA-256の64文字lowercase hexが必須です");
        }
    }
}
