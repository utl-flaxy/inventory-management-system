package com.example.demo.exception;

public class InventoryAdjustmentConflictException extends RuntimeException {
    public InventoryAdjustmentConflictException() {
        super("現在の在庫にこの調整を適用できません");
    }
}
