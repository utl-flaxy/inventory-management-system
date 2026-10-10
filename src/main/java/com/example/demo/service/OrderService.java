package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.OrderInventoryRestorationException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EntityManager entityManager;

    // 注文作成
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order createOrder(OrderRequest request) {

        // Controllerを経由しない呼び出しでも、読み取り・更新前に不正入力を拒否する
        if (request == null || request.getProductId() == null || request.getProductId() <= 0) {
            throw new InvalidOrderRequestException("商品IDは正の値を入力してください");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new InvalidOrderRequestException("数量は1以上を入力してください");
        }

        Product product = productRepository.findByIdForUpdate(request.getProductId())
                .orElseThrow(() ->
                        new RuntimeException("商品が存在しません"));

        // 呼び出し元のPersistence Contextに古い商品がある場合も、ロック下の現在値を使う
        entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);

        // Integerの金額上限を超える数量は、在庫変更前に拒否する
        int totalPrice;
        try {
            totalPrice = Math.multiplyExact(product.getPrice(), request.getQuantity());
        } catch (ArithmeticException ex) {
            throw new InvalidOrderRequestException("注文金額が上限を超えています");
        }

        // 在庫減算
        product.decreaseStock(request.getQuantity());
        productRepository.save(product);

        // 注文作成
        Order order = new Order();
        order.setTotalPrice(totalPrice);

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
                .orElseThrow(OrderNotFoundException::new);
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

    // 既存APIの互換入口。必ず業務コマンドを経由する
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order updateStatus(Long id, OrderStatus status) {

        if (status == null) {
            throw new InvalidOrderRequestException("注文ステータスは必須です");
        }
        return switch (status) {
            case SHIPPED -> shipOrder(id);
            case COMPLETED -> completeOrder(id);
            case CANCELLED -> cancelOrder(id);
            case PENDING -> {
                Order order = findByIdForUpdate(id);
                // PENDINGは遷移先にできないため、ドメインの検証で拒否する
                order.validateTransitionTo(status);
                yield order;
            }
        };
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order shipOrder(Long id) {
        Order order = findByIdForUpdate(id);
        order.ship();
        return orderRepository.save(order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order completeOrder(Long id) {
        Order order = findByIdForUpdate(id);
        order.complete();
        return orderRepository.save(order);
    }

    // 注文キャンセル
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order cancelOrder(Long id) {

        Order order = findByIdForUpdate(id);
        order.validateTransitionTo(OrderStatus.CANCELLED);

        // Order -> ProductのID昇順。関連読み取りで先にロードされた商品もrefreshしてから計算する
        order.getOrderItems().stream().map(OrderItem::getProduct)
                .distinct().sorted(Comparator.comparing(Product::getId))
                .forEach(product -> entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE));

        // 全明細の復元後在庫を変更前に検証。同じ商品が複数明細にあっても累計する
        Map<Product, Integer> restoredStocks = new TreeMap<>(Comparator.comparing(Product::getId));
        for (OrderItem item : order.getOrderItems()) {
            Product product = item.getProduct();
            Integer quantity = item.getQuantity();
            if (quantity == null || quantity <= 0) {
                throw new OrderInventoryRestorationException("復元する数量は1以上である必要があります");
            }
            try {
                int stock = restoredStocks.getOrDefault(product, product.getStock());
                restoredStocks.put(product, Math.addExact(stock, quantity));
            } catch (ArithmeticException ex) {
                throw new OrderInventoryRestorationException("在庫復元で在庫数の上限を超えます", ex);
            }
        }

        for (Map.Entry<Product, Integer> entry : restoredStocks.entrySet()) {
            entry.getKey().setStock(entry.getValue());
            productRepository.save(entry.getKey());
        }
        order.cancel();
        return orderRepository.save(order);
    }

    private Order findByIdForUpdate(Long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return order;
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
