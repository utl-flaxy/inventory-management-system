package com.example.demo.exception;

public class AuthMutationRateLimitExceededException extends RuntimeException {
    public static final String DETAIL = "認証設定の変更回数が上限に達しました。時間をおいて再試行してください";
    private final long retryAfterSeconds;
    public AuthMutationRateLimitExceededException(long retryAfterSeconds) {
        super(DETAIL);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
