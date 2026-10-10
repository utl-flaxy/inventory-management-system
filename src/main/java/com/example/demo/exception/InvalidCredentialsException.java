package com.example.demo.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("ユーザー名またはパスワードが正しくありません"); }
}
