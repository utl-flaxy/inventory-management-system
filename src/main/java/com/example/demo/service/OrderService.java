package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    @Transactional
    public Order createOrder(OrderRequest request) {

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("商品が存在しません"));

        if (product.getStock() < request.getQuantity()) {
            throw new OutOfStockException("在庫が不足しています");
        }

        // 在庫減算
        product.setStock(product.getStock() - request.getQuantity());
        productRepository.save(product);

        // 合計金額
        int totalPrice = product.getPrice() * request.getQuantity();

        // 注文作成
        Order order = new Order();
        order.setTotalPrice(totalPrice);

        // 初期ステータス
        order.setStatus(OrderStatus.PENDING);

        Order savedOrder = orderRepository.save(order);

        // 注文明細作成
        OrderItem orderItem = new OrderItem();
        orderItem.setOrder(savedOrder);
        orderItem.setProduct(product);
        orderItem.setQuantity(request.getQuantity());
        orderItem.setPrice(product.getPrice());

        orderItemRepository.save(orderItem);

        return savedOrder;
    }

    // 注文一覧取得
    public List<Order> findAll() {

        return orderRepository.findAll();
    }

    // 売上集計
    public SalesResponse getSales() {

        Integer totalSales = orderRepository.getTotalSales();

        Long totalOrders = orderRepository.count();

        return new SalesResponse(
                totalSales,
                totalOrders
        );
    }

    // 注文ステータス更新
    @Transactional
    public Order updateStatus(Long id, OrderStatus status) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("注文が存在しません"));

        order.setStatus(status);

        return orderRepository.save(order);
    }
}