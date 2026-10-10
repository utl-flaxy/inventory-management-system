package com.example.demo.exception;

public class OrderInventoryRestorationException extends RuntimeException {

    public OrderInventoryRestorationException(String message) {
        super(message);
    }

    public OrderInventoryRestorationException(String message, Throwable cause) {
        super(message, cause);
    }
}
