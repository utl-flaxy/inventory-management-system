package com.example.demo.entity;

import com.example.demo.exception.IllegalOrderStateTransitionException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_orders_customer"))
    private User customer;

    @Column(nullable = false, updatable = false)
    private Integer totalPrice = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant orderedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    // primitiveならSpring Dataの新規判定はidを使い、初回saveをpersistとして扱う
    private long version;

    // refresh時に明細・商品まで暗黙ロックせず、Serviceで商品ID順にロックする
    @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE,
            CascadeType.REMOVE, CascadeType.DETACH})
    private List<OrderItem> orderItems = new ArrayList<>();

    public static Order create(User customer, List<OrderItem> items) {
        if (customer == null) {
            throw new IllegalArgumentException("注文者は必須です");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("注文明細は1件以上必要です");
        }
        Order order = new Order();
        order.customer = customer;
        order.orderedAt = Instant.now();
        order.updatedAt = order.orderedAt;
        items.forEach(order::addItem);
        return order;
    }

    // 作成時だけ明細を追加し、関連と合計を同時に確定する。永続化後の明細編集は公開しない。
    private void addItem(OrderItem item) {
        if (item == null || item.getOrder() != null) {
            throw new IllegalArgumentException("明細が未指定、または既に注文に属しています");
        }
        int nextTotal = Math.addExact(totalPrice, item.getLineTotal());
        item.attachTo(this);
        orderItems.add(item);
        totalPrice = nextTotal;
    }

    public List<OrderItem> getOrderItems() {
        return Collections.unmodifiableList(orderItems);
    }

    @PrePersist
    private void validateAggregate() {
        if (customer == null || orderedAt == null || updatedAt == null || orderItems.isEmpty()) {
            throw new IllegalArgumentException("注文者・日時・注文明細は必須です");
        }
        int sum = 0;
        for (OrderItem item : orderItems) {
            if (item.getOrder() != this) {
                throw new IllegalArgumentException("注文と明細の関連が不正です");
            }
            sum = Math.addExact(sum, item.getLineTotal());
        }
        if (sum != totalPrice) {
            throw new IllegalArgumentException("注文合計が明細合計と一致しません");
        }
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public void ship() {
        transitionTo(OrderStatus.SHIPPED);
    }

    public void complete() {
        transitionTo(OrderStatus.COMPLETED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    public void validateTransitionTo(OrderStatus target) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalOrderStateTransitionException(status, target);
        }
    }

    private void transitionTo(OrderStatus target) {
        validateTransitionTo(target);
        status = target;
    }
}
