package com.example.demo.exception;

import com.example.demo.entity.OrderStatus;

public class IllegalOrderStateTransitionException extends RuntimeException {

    public IllegalOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super("注文ステータスを " + from + " から " + to + " へ変更できません");
    }
}
