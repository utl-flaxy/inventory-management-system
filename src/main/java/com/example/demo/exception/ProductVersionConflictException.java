package com.example.demo.exception;

public class ProductVersionConflictException extends RuntimeException {

    public ProductVersionConflictException() {
        super("商品が更新されています。最新のversionで再試行してください");
    }
}
