package com.example.demo.dto;

// CSV専用のscalar projection。EntityやLAZY関連をpersistence contextへ保持しない。
public record OrderCsvRow(Long orderId, Long itemId, String productName, Integer quantity,
                          Long unitPrice, String status) {
    public long lineTotal() {
        return Math.multiplyExact(unitPrice, quantity.longValue());
    }
}
