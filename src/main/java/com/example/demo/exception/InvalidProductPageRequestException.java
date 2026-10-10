package com.example.demo.exception;

public class InvalidProductPageRequestException extends RuntimeException {
    public InvalidProductPageRequestException() {
        super("Invalid product page request");
    }
}
