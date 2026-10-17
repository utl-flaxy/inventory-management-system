package com.example.demo.exception;

public class RegistrationRateLimitExceededException extends RuntimeException {
    public static final String DETAIL = "登録試行回数が上限に達しました。時間をおいて再試行してください";
    private final long retryAfterSeconds;

    public RegistrationRateLimitExceededException(long retryAfterSeconds) {
        super(DETAIL);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
