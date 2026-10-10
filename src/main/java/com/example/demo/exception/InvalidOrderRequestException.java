package com.example.demo.exception;

public class InvalidOrderRequestException extends IllegalArgumentException {

    public InvalidOrderRequestException(String message) {
        super(message);
    }
}
