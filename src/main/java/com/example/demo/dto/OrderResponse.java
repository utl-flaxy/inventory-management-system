package com.example.demo.dto;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, Instant orderedAt, Instant updatedAt,
                            Integer totalPrice, Long version, CustomerResponse customer,
                            List<OrderItemResponse> items) {
    public record CustomerResponse(Long id, String username) { }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getOrderedAt(), order.getUpdatedAt(),
                order.getTotalPrice(), order.getVersion(),
                new CustomerResponse(order.getCustomer().getId(), order.getCustomer().getUsername()),
                order.getOrderItems().stream().map(OrderItemResponse::from).toList());
    }
}
