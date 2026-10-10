package com.example.demo.entity;

public enum OrderStatus {

    PENDING,     // 注文受付
    SHIPPED,     // 発送済み
    COMPLETED,   // 完了
    CANCELLED;   // キャンセル

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
