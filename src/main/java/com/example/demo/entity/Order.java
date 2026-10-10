package com.example.demo.entity;

import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<OrderItem> orderItems = new ArrayList<>();

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
