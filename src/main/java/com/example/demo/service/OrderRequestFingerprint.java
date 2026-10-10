package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

public final class OrderRequestFingerprint {
    private OrderRequestFingerprint() { }

    // 重複ID/不正数量はServiceの検証で拒否してから渡す。価格・在庫等は含めない。
    public static String fromValidatedItems(Map<Long, Integer> quantities) {
        String canonical = quantities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(item -> item.getKey() + ":" + item.getValue()).collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }
}
