package com.example.demo.exception;

public class InvalidNewPasswordException extends RuntimeException {
    public static final String DETAIL = "ADMINの新パスワードは16〜72文字、UTF-8で72 bytes以内とし、制御文字を含めないでください";
    public InvalidNewPasswordException() { super(DETAIL); }
}
