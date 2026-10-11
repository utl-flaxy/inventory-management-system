package com.example.demo.repository;

import com.example.demo.dto.SalesResponse;
import com.example.demo.dto.OrderPageRow;
import com.example.demo.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = "select o.id from Order o", countQuery = "select count(o.id) from Order o")
    Page<Long> findOrderIds(Pageable pageable);

    @Query(value = "select o.id from Order o where o.customer.id = :customerId",
            countQuery = "select count(o.id) from Order o where o.customer.id = :customerId")
    Page<Long> findOrderIdsByCustomerId(Long customerId, Pageable pageable);

    // Pageableをcollection JOINに適用しない。最大100個の注文IDに限定した関連取得。
    @Query("""
            select new com.example.demo.dto.OrderPageRow(o.id, o.status, o.orderedAt, o.updatedAt,
                o.completedAt, o.totalPrice, o.version, c.id, c.username, i.id, i.product.id,
                i.productNameSnapshot, i.unitPrice, i.quantity)
            from Order o join o.customer c left join o.orderItems i
            where o.id in :ids
            order by o.id desc, i.id asc
            """)
    List<OrderPageRow> findPageRows(List<Long> ids);

    @Query("""
            select new com.example.demo.dto.OrderPageRow(o.id, o.status, o.orderedAt, o.updatedAt,
                o.completedAt, o.totalPrice, o.version, c.id, c.username, i.id, i.product.id,
                i.productNameSnapshot, i.unitPrice, i.quantity)
            from Order o join o.customer c left join o.orderItems i
            where o.id in :ids and o.customer.id = :customerId
            order by o.id desc, i.id asc
            """)
    List<OrderPageRow> findPageRowsByCustomerId(List<Long> ids, Long customerId);

    Optional<Order> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerId(Long id, Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.customer.id = :customerId")
    Optional<Order> findByIdAndCustomerIdForUpdate(Long id, Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(Long id);

    @Query("""
            SELECT new com.example.demo.dto.SalesResponse(COALESCE(SUM(o.totalPrice), 0), COUNT(o.id))
            FROM Order o
            WHERE o.status = com.example.demo.entity.OrderStatus.COMPLETED
              AND o.completedAt IS NOT NULL
              AND (:from IS NULL OR o.completedAt >= :from)
              AND (:to IS NULL OR o.completedAt < :to)
            """)
    SalesResponse aggregateSales(Instant from, Instant to);

    default Long getTotalSales() {
        return aggregateSales(null, null).getTotalSales();
    }

}
