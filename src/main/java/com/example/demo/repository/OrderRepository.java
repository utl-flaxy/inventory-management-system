package com.example.demo.repository;

import com.example.demo.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("""
            SELECT COALESCE(SUM(o.totalPrice), 0)
            FROM Order o
            """)
    Integer getTotalSales();

}