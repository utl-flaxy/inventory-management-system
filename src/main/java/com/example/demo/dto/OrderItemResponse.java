package com.example.demo.dto;

import com.example.demo.entity.OrderItem;

public record OrderItemResponse(Long productId, String productName, Integer unitPrice,
                                Integer quantity, Integer lineTotal) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getProduct().getId(), item.getProductNameSnapshot(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal());
    }
}
