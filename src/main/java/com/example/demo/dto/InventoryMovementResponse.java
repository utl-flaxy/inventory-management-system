package com.example.demo.dto;

import com.example.demo.entity.InventoryMovement;
import com.example.demo.entity.InventoryMovementReason;
import com.example.demo.entity.InventorySourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record InventoryMovementResponse(Long id, Long productId, Integer delta, Integer stockBefore,
        Integer stockAfter, InventoryMovementReason reason, InventorySourceType sourceType,
        @Schema(types = {"integer", "null"}, format = "int64") Long sourceId,
        OrderResponse.CustomerResponse actor,
        @Schema(types = {"string", "null"}, maxLength = 500) String note, Instant createdAt) {
    public static InventoryMovementResponse from(InventoryMovement movement) {
        return new InventoryMovementResponse(movement.getId(), movement.getProduct().getId(), movement.getDelta(),
                movement.getStockBefore(), movement.getStockAfter(), movement.getReason(), movement.getSourceType(),
                movement.getSourceId(), new OrderResponse.CustomerResponse(movement.getActor().getId(),
                movement.getActor().getUsername()), movement.getNote(), movement.getCreatedAt());
    }
}
