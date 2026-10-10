package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "inventory_movements", indexes =
        @Index(name = "idx_inventory_movements_product_created_id", columnList = "product_id,created_at,id"))
@Immutable
@Check(name = "ck_inventory_movement_delta", constraints = "delta <> 0")
@Check(name = "ck_inventory_movement_stocks", constraints = "stock_before >= 0 AND stock_after >= 0 AND stock_after = stock_before + delta")
@Check(name = "ck_inventory_movement_reason", constraints = "reason IN ('ORDER_CREATED','ORDER_CANCELLED','RECEIPT','DAMAGE','STOCKTAKE','CORRECTION')")
@Check(name = "ck_inventory_movement_source", constraints = "(source_type = 'ORDER' AND source_id IS NOT NULL AND note IS NULL AND ((reason = 'ORDER_CREATED' AND delta < 0) OR (reason = 'ORDER_CANCELLED' AND delta > 0))) OR (source_type = 'MANUAL' AND source_id IS NULL AND reason IN ('RECEIPT','DAMAGE','STOCKTAKE','CORRECTION'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryMovement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, updatable = false)
    private Integer delta;
    @Column(name = "stock_before", nullable = false, updatable = false)
    private Integer stockBefore;
    @Column(name = "stock_after", nullable = false, updatable = false)
    private Integer stockAfter;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32, updatable = false)
    private InventoryMovementReason reason;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 16, updatable = false)
    private InventorySourceType sourceType;
    // V5でorders FKを付与。MANUALはnull、ORDERは確定済みorder.id。
    @Column(name = "source_id", updatable = false)
    private Long sourceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false, updatable = false)
    private User actor;

    @Column(length = 500, updatable = false)
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static InventoryMovement create(Product product, Integer delta, Integer before, Integer after,
            InventoryMovementReason reason, InventorySourceType sourceType, Long sourceId,
            User actor, String note, Instant createdAt) {
        if (product == null || product.getId() == null || product.getId() <= 0
                || actor == null || actor.getId() == null || actor.getId() <= 0
                || delta == null || delta == 0 || before == null || before < 0 || after == null || after < 0
                || reason == null || sourceType == null || createdAt == null) {
            throw new IllegalArgumentException("在庫履歴の必須値が不正です");
        }
        if ((long) before + delta != after) throw new IllegalArgumentException("在庫履歴の前後値が一致しません");
        String normalized = note == null || note.isBlank() ? null : note;
        if (note != null && note.length() > 500) throw new IllegalArgumentException("noteが上限を超えています");
        boolean orderSource = sourceType == InventorySourceType.ORDER;
        if (reason.isManual() != (sourceType == InventorySourceType.MANUAL)
                || (orderSource ? sourceId == null || sourceId <= 0 : sourceId != null)
                || (orderSource && normalized != null)
                || (reason == InventoryMovementReason.ORDER_CREATED && delta > 0)
                || (reason == InventoryMovementReason.ORDER_CANCELLED && delta < 0)) {
            throw new IllegalArgumentException("在庫履歴の理由と操作元が一致しません");
        }
        InventoryMovement movement = new InventoryMovement();
        movement.product = product;
        movement.delta = delta;
        movement.stockBefore = before;
        movement.stockAfter = after;
        movement.reason = reason;
        movement.sourceType = sourceType;
        movement.sourceId = sourceId;
        movement.actor = actor;
        movement.note = normalized;
        movement.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        return movement;
    }
}
