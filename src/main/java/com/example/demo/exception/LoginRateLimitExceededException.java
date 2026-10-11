package com.example.demo.exception;

public class LoginRateLimitExceededException extends RuntimeException {
    public static final String DETAIL = "ログイン試行回数が上限に達しました。時間をおいて再試行してください";
    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(long retryAfterSeconds) {
        super(DETAIL);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
