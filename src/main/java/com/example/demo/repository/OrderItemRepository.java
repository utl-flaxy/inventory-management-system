package com.example.demo.repository;

import com.example.demo.dto.OrderCsvRow;
import com.example.demo.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    boolean existsByProductId(Long productId);

    // LIMIT付き派生テーブルは先に最大500明細を確定し、JOIN後のsortもその範囲だけにする。
    @Query(value = """
            SELECT i.order_id AS orderId, i.id AS itemId, i.product_name_snapshot AS productName,
                   i.quantity, i.price AS unitPrice, o.status
            FROM (
                SELECT id, order_id, product_name_snapshot, quantity, price
                FROM order_items
                WHERE order_id >= :orderId AND
                    (order_id > :orderId OR (order_id = :orderId AND id > :itemId))
                ORDER BY order_id ASC, id ASC
                LIMIT :limit
            ) i JOIN orders o ON o.id = i.order_id
            ORDER BY i.order_id ASC, i.id ASC
            """, nativeQuery = true)
    List<OrderCsvRow> findCsvBatch(@Param("orderId") long orderId,
                                  @Param("itemId") long itemId, @Param("limit") int limit);
}
