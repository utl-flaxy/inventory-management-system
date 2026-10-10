package com.example.demo.dto;

import com.example.demo.entity.InventoryMovementReason;

// 注文理由はmanual requestのenumとして受け付けない。
public enum ManualInventoryReason {
    RECEIPT, DAMAGE, STOCKTAKE, CORRECTION;

    public InventoryMovementReason movementReason() {
        return InventoryMovementReason.valueOf(name());
    }
}
