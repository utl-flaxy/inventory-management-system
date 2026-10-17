package com.example.demo.exception;

public class RegistrationSourceUnavailableException extends RuntimeException {
    public static final String DETAIL = "登録接続元を確認できません。運用者にお問い合わせください";
    public RegistrationSourceUnavailableException() { super(DETAIL); }
}
