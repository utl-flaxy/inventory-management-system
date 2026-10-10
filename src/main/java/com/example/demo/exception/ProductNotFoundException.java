package com.example.demo.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException() {
        super("商品が存在しません");
    }
}
