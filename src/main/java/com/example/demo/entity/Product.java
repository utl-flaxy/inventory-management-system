package com.example.demo.entity;

import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.InventoryAdjustmentConflictException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "products")
@Check(name = "ck_products_stock_nonnegative", constraints = "stock IS NOT NULL AND stock >= 0")
@Check(name = "ck_products_price_nonnegative", constraints = "price >= 0")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Integer stock;

    @Version
    @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    @Setter(AccessLevel.NONE)
    private Long version = 0L;

    @Builder
    public Product(Long id, String name, Long price, Integer stock) {
        this.id = id;
        this.name = name;
        this.price = price;
        setStock(stock);
    }

    private void setStock(Integer stock) {
        if (stock == null || stock < 0) {
            throw new IllegalArgumentException("在庫数は0以上を入力してください");
        }
        this.stock = stock;
    }

    public void decreaseStock(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("数量は1以上を入力してください");
        }
        if (stock < quantity) {
            throw new OutOfStockException("在庫が不足しています");
        }
        adjustStock(-quantity);
    }

    public void increaseStock(Integer quantity) {
        if (quantity == null || quantity <= 0)
            throw new IllegalArgumentException("数量は1以上を入力してください");
        adjustStock(quantity);
    }

    public void adjustStock(Integer delta) {
        if (delta == null || delta == 0)
            throw new IllegalArgumentException("在庫差分は0以外を入力してください");
        int after;
        try {
            after = Math.addExact(stock, delta);
        } catch (ArithmeticException ex) {
            throw new InventoryAdjustmentConflictException();
        }
        if (after < 0) throw new InventoryAdjustmentConflictException();
        setStock(after);
    }

    @PrePersist
    @PreUpdate
    private void validateStock() {
        setStock(stock);
    }
}
