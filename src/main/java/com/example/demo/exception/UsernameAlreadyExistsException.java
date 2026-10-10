package com.example.demo.exception;

public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException() { super("ユーザー名は既に使用されています"); }
    public UsernameAlreadyExistsException(Throwable cause) { super("ユーザー名は既に使用されています", cause); }
}
