package com.example.demo.repository;

import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByCustomerId(Long customerId);

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
