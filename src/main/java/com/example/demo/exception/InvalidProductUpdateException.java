package com.example.demo.exception;

public class InvalidProductUpdateException extends RuntimeException {

    public InvalidProductUpdateException() {
        super("更新元のversionは0以上の値で指定してください");
    }
}
