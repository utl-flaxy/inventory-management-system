package com.example.demo.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("同じIdempotency-Keyに対する注文内容が競合しています");
    }

    public IdempotencyConflictException(Throwable cause) {
        super("Idempotency-Keyの注文が他の処理で保存されています。同じ内容で再送してください", cause);
    }
}
