package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "order_items")
@Check(name = "ck_order_items_quantity_positive", constraints = "quantity IS NOT NULL AND quantity > 0")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer quantity;

    private Integer price;

    @ManyToOne
    @JoinColumn(name = "order_id")
    @JsonBackReference
    private Order order;

    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;

    public OrderItem(Long id, Integer quantity, Integer price, Order order, Product product) {
        this.id = id;
        setQuantity(quantity);
        this.price = price;
        this.order = order;
        this.product = product;
    }

    public void setQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("数量は1以上を入力してください");
        }
        this.quantity = quantity;
    }

    @PrePersist
    @PreUpdate
    private void validateQuantity() {
        setQuantity(quantity);
    }
}
