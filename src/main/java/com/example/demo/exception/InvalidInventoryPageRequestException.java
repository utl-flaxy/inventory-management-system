package com.example.demo.exception;

public class InvalidInventoryPageRequestException extends RuntimeException {
    public InvalidInventoryPageRequestException() {
        super("Invalid inventory page request");
    }
}
