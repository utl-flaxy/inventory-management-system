package com.example.demo.exception;

public class InvalidProductSearchKeywordException extends RuntimeException {
    public InvalidProductSearchKeywordException() {
        super("Invalid product search keyword");
    }
}
