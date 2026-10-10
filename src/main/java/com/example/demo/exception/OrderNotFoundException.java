package com.example.demo.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("注文が存在しません");
    }
}
