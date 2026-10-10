package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "order_items")
@Check(name = "ck_order_items_quantity_positive", constraints = "quantity IS NOT NULL AND quantity > 0")
@Check(name = "ck_order_items_price_nonnegative", constraints = "price >= 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    // 既存price列を注文時単価として維持し、破壊的な列名変更を避ける
    @Column(name = "price", nullable = false, updatable = false)
    private Long unitPrice;

    @Column(nullable = false, updatable = false)
    private String productNameSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    public static OrderItem create(Product product, Integer quantity) {
        validateQuantity(quantity);
        if (product == null || product.getName() == null || product.getPrice() == null
                || product.getPrice() < 0) {
            throw new IllegalArgumentException("商品名と非負の単価は必須です");
        }
        OrderItem item = new OrderItem();
        item.product = product;
        item.quantity = quantity;
        item.unitPrice = product.getPrice();
        item.productNameSnapshot = product.getName();
        return item;
    }

    void attachTo(Order order) {
        if (order == null || this.order != null) {
            throw new IllegalArgumentException("明細の注文を差し替えることはできません");
        }
        this.order = order;
    }

    public long getLineTotal() {
        return Math.multiplyExact(unitPrice, quantity.longValue());
    }

    @PrePersist
    @PreUpdate
    private void validateQuantity() {
        validateQuantity(quantity);
    }

    private static void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("数量は1以上を入力してください");
        }
    }
}
