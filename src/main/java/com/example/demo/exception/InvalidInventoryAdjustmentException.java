package com.example.demo.exception;

public class InvalidInventoryAdjustmentException extends RuntimeException {
    public InvalidInventoryAdjustmentException() {
        super("在庫調整の入力内容が不正です");
    }
}
