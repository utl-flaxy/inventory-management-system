package com.example.demo.repository;

import com.example.demo.entity.InventoryMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

// Creation/queryだけを公開。update/deleteの入口を作らない。
public interface InventoryMovementRepository extends Repository<InventoryMovement, Long> {
    InventoryMovement save(InventoryMovement movement);

    @EntityGraph(attributePaths = "actor")
    Page<InventoryMovement> findByProductId(Long productId, Pageable pageable);
}
