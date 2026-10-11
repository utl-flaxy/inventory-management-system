package com.example.demo.dto;

import com.example.demo.entity.OrderStatus;

import java.time.Instant;

// ページ内の注文・明細だけを読むscalar projection。Userのhash/注文の冪等keyやProduct本体は取得しない。
public record OrderPageRow(Long id, OrderStatus status, Instant orderedAt, Instant updatedAt,
                           Instant completedAt, Long totalPrice, Long version, Long customerId,
                           String username, Long itemId, Long productId, String productName,
                           Long unitPrice, Integer quantity) { }
