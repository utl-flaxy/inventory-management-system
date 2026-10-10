package com.example.demo.service;

import com.example.demo.dto.InventoryAdjustmentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class InventoryAdjustmentFingerprint {
    private InventoryAdjustmentFingerprint() { }

    public static String normalizeNote(String note) {
        return note == null || note.isBlank() ? null : note;
    }

    // 検証済みDTOの意味値を使う。自由文字列のnoteはUTF-8 byte長で区切り、NULLと区別する。
    public static String fromValidatedRequest(Long productId, InventoryAdjustmentRequest request) {
        String note = normalizeNote(request.getNote());
        String canonical = "inventory-adjustment:v1\nproductId:" + productId
                + "\ndelta:" + request.getDelta() + "\nreason:" + request.getReason().name()
                + "\nnote:" + (note == null ? "null" : "utf8:" + note.getBytes(StandardCharsets.UTF_8).length + ":" + note);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }
}
