package com.example.demo.entity;

import java.util.regex.Pattern;

// 注文と手動在庫調整で共有するopaque key。正規化しない。
public final class IdempotencyKey {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private IdempotencyKey() { }

    public static void validate(String key) {
        if (key == null || !KEY.matcher(key).matches())
            throw new IllegalArgumentException("Idempotency-Keyは1〜128文字の英数字または._:-を指定してください");
    }
}
