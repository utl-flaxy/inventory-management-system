package com.example.demo.entity;

public enum InventoryMovementReason {
    ORDER_CREATED, ORDER_CANCELLED, RECEIPT, DAMAGE, STOCKTAKE, CORRECTION;

    public boolean isManual() {
        return this != ORDER_CREATED && this != ORDER_CANCELLED;
    }
}
