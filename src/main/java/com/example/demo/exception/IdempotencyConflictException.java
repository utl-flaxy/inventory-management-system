package com.example.demo.exception;

public class IdempotencyConflictException extends RuntimeException {
    private IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public static IdempotencyConflictException inventoryAdjustment(Throwable cause) {
        return new IdempotencyConflictException("同じIdempotency-Keyに対する在庫調整内容が競合しています", cause);
    }

    public IdempotencyConflictException() {
        super("同じIdempotency-Keyに対する注文内容が競合しています");
    }

    public IdempotencyConflictException(Throwable cause) {
        super("Idempotency-Keyの注文が他の処理で保存されています。同じ内容で再送してください", cause);
    }
}
