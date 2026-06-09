package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // 注文登録
    @PostMapping
    public Order create(@RequestBody OrderRequest request) {
        return orderService.createOrder(request);
    }

    // 注文一覧取得
    @GetMapping
    public List<Order> getAll() {
        return orderService.findAll();
    }
}