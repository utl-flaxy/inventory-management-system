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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    // 注文作成
    @Transactional
    public Order createOrder(OrderRequest request) {

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() ->
                        new RuntimeException("商品が存在しません"));

        // 在庫チェック
        if (product.getStock() < request.getQuantity()) {
            throw new OutOfStockException("在庫が不足しています");
        }

        // 在庫減算
        product.setStock(
                product.getStock() - request.getQuantity()
        );
        productRepository.save(product);

        // 合計金額
        int totalPrice = product.getPrice() * request.getQuantity();

        // 注文作成
        Order order = new Order();
        order.setTotalPrice(totalPrice);
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

    // 注文詳細取得
    public Order findById(Long id) {

        return orderRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("注文が存在しません"));
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
                .orElseThrow(() ->
                        new RuntimeException("注文が存在しません"));

        order.setStatus(status);

        return orderRepository.save(order);
    }

    // 注文キャンセル
    @Transactional
    public Order cancelOrder(Long id) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("注文が存在しません"));

        // キャンセル済みチェック
        if (order.getStatus() == OrderStatus.CANCELLED) {

            throw new RuntimeException("既にキャンセル済みです");
        }

        // 発送済み・完了済みはキャンセル不可
        if (order.getStatus() == OrderStatus.SHIPPED
                || order.getStatus() == OrderStatus.COMPLETED) {

            throw new RuntimeException("この注文はキャンセルできません");
        }

        // 在庫を戻す
        for (OrderItem item : order.getOrderItems()) {

            Product product = item.getProduct();

            product.setStock(
                    product.getStock() + item.getQuantity()
            );

            productRepository.save(product);
        }

        // ステータス変更
        order.setStatus(OrderStatus.CANCELLED);

        return orderRepository.save(order);
    }

    // CSV出力
    public ByteArrayInputStream exportCsv() {

        List<Order> orders = orderRepository.findAll();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out);

        // ヘッダ
        writer.println("注文ID,商品名,数量,単価,合計金額,ステータス");

        // データ
        for (Order order : orders) {

            for (OrderItem item : order.getOrderItems()) {

                writer.printf(
                        "%d,%s,%d,%d,%d,%s%n",
                        order.getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        item.getPrice(),
                        item.getPrice() * item.getQuantity(),
                        order.getStatus()
                );
            }
        }

        writer.flush();

        return new ByteArrayInputStream(
                out.toByteArray()
        );
    }
}