package com.example.demo.exception;

public class AuthMutationSourceUnavailableException extends RuntimeException {
    public static final String DETAIL = "認証設定変更の接続元を確認できません。運用者にお問い合わせください";
    public AuthMutationSourceUnavailableException() { super(DETAIL); }
}
