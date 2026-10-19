package com.example.demo.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() { super("対象ユーザーが見つかりません"); }
}
