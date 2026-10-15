package com.example.demo.exception;

/** Missing verified client identity is an operational failure, never a shared rate-limit key. */
public class LoginSourceUnavailableException extends RuntimeException {
    public static final String DETAIL = "ログイン接続元を確認できません。運用者にお問い合わせください";
    public LoginSourceUnavailableException() { super(DETAIL); }
}
