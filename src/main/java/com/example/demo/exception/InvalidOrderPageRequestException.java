package com.example.demo.exception;

public class InvalidOrderPageRequestException extends RuntimeException {
    public InvalidOrderPageRequestException() {
        super("Invalid order page request");
    }
}
