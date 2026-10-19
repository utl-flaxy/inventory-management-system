package com.example.demo;

import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

public final class TestOrders {
    private TestOrders() { }
    private static final String CUSTOMER_UID = java.util.UUID.randomUUID().toString();

    public static String key() { return java.util.UUID.randomUUID().toString(); }

    public static User customer() {
        return User.builder().id(1L).authUid(CUSTOMER_UID).username("alice").password("test-password-hash").role("ROLE_USER").build();
    }

    public static OrderRequest request(Long productId, Integer quantity) {
        return request(new OrderItemRequest(productId, quantity));
    }

    public static OrderRequest request(OrderItemRequest... items) {
        OrderRequest request = new OrderRequest();
        request.setItems(Arrays.asList(items));
        return request;
    }

    public static Order order(Long id, OrderStatus status, OrderItem... items) {
        Order order = Order.create(customer(), List.of(items));
        switch (status) {
            case SHIPPED -> order.ship();
            case COMPLETED -> { order.ship(); order.complete(java.time.Instant.parse("2026-10-15T12:00:00Z")); }
            case CANCELLED -> order.cancel();
            case PENDING -> { }
        }
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    public static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new com.example.demo.security.AuthPrincipal(1L, CUSTOMER_UID, 0, "ROLE_USER"), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    }
}
