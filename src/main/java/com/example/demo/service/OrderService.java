package com.example.demo.service;

import com.example.demo.dto.OrderCsvRow;
import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.IdempotencyKey;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.InventoryMovementReason;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.InvalidIdempotencyKeyException;
import com.example.demo.exception.IdempotencyConflictException;
import com.example.demo.exception.OrderInventoryRestorationException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class OrderService {

    public static final int CSV_BATCH_SIZE = 500;
    private final OrderItemRepository orderItemRepository;

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CurrentUser currentUser;
    private final EntityManager entityManager;
    private final Clock clock;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;

    // 注文作成
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order createOrder(OrderRequest request, String idempotencyKey) {

        try {
            IdempotencyKey.validate(idempotencyKey);
        } catch (IllegalArgumentException ex) {
            throw new InvalidIdempotencyKeyException(ex.getMessage());
        }

        // DTO Validationに依存せず、全明細を読み取り・更新前に検証する
        Map<Long, Integer> quantities = validatedQuantities(request);
        String fingerprint = OrderRequestFingerprint.fromValidatedItems(quantities);
        User authenticated = currentUser.requireUser();
        // 同customerのcreateを直列化。User -> Product IDs昇順、既存Orderは通常queryで読む。
        User customer = userRepository.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証ユーザーが存在しません"));
        var existing = orderRepository.findByCustomerIdAndIdempotencyKey(customer.getId(), idempotencyKey);
        if (existing.isPresent()) {
            Order order = existing.get();
            if (!fingerprint.equals(order.getRequestFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return responseReady(order);
        }
        Map<Long, Product> products = new TreeMap<>();
        for (Long productId : quantities.keySet()) {
            Product product = productRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new InvalidOrderRequestException("商品が存在しません"));
            // 古いPersistence Contextの値も、ロック下の現在値に更新する
            entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
            products.put(productId, product);
        }

        // 全商品をID昇順でロックした後、在庫・スナップショット・合計を変更前に検証する
        List<OrderItem> items = new ArrayList<>();
        Order order;
        try {
            for (Map.Entry<Long, Product> entry : products.entrySet()) {
                int quantity = quantities.get(entry.getKey());
                if (entry.getValue().getStock() < quantity) {
                    throw new OutOfStockException("在庫が不足しています");
                }
                items.add(OrderItem.create(entry.getValue(), quantity));
            }
            order = Order.create(customer, items, idempotencyKey, fingerprint);
        } catch (ArithmeticException ex) {
            throw new InvalidOrderRequestException("注文金額が上限を超えています");
        } catch (IllegalArgumentException ex) {
            throw new InvalidOrderRequestException(ex.getMessage());
        }

        Map<Long, Integer> beforeStocks = new TreeMap<>();
        for (Map.Entry<Long, Product> entry : products.entrySet()) {
            beforeStocks.put(entry.getKey(), entry.getValue().getStock());
            entry.getValue().decreaseStock(quantities.get(entry.getKey()));
            productRepository.save(entry.getValue());
        }
        // 明細はOrderからcascade保存する。途中の永続化失敗も同じTransactionでrollbackする。
        try {
            Order saved = orderRepository.save(order);
            for (Map.Entry<Long, Product> entry : products.entrySet()) {
                inventoryService.recordOrderChange(entry.getValue(), beforeStocks.get(entry.getKey()),
                        -quantities.get(entry.getKey()), saved, InventoryMovementReason.ORDER_CREATED, customer);
            }
            return responseReady(saved);
        } catch (DataIntegrityViolationException ex) {
            // rollback-onlyのTransaction内で再照会/replayしない。限定したUNIQUE違反だけ409。
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 1062
                        && sql.getMessage() != null && sql.getMessage().contains("uk_orders_customer_idempotency_key")) {
                    throw new IdempotencyConflictException(ex);
                }
            }
            throw ex;
        }
    }

    private Map<Long, Integer> validatedQuantities(OrderRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new InvalidOrderRequestException("注文明細は1件以上必要です");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (OrderItemRequest item : request.getItems()) {
            if (item == null || item.getProductId() == null || item.getProductId() <= 0) {
                throw new InvalidOrderRequestException("商品IDは正の値を入力してください");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new InvalidOrderRequestException("数量は1以上を入力してください");
            }
            if (quantities.putIfAbsent(item.getProductId(), item.getQuantity()) != null) {
                throw new InvalidOrderRequestException("同じ商品を複数の明細に指定できません");
            }
        }
        return quantities;
    }

    // 自分の注文をDB query段階で絞り、全件取得や取得後の所有者判定を行わない
    @Transactional(readOnly = true)
    public List<Order> findMyOrders() {
        Long customerId = currentUser.requireUser().getId();
        return orderRepository.findAllByCustomerId(customerId).stream().map(this::responseReady).toList();
    }

    @Transactional(readOnly = true)
    public Order findMyOrder(Long id) {
        Long customerId = currentUser.requireUser().getId();
        return responseReady(orderRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(OrderNotFoundException::new));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order cancelMyOrder(Long id) {
        Long customerId = currentUser.requireUser().getId();
        // 所有者条件と排他ロックを同じqueryに含め、通常find後の再lockを避ける
        Order order = orderRepository.findByIdAndCustomerIdForUpdate(id, customerId)
                .orElseThrow(OrderNotFoundException::new);
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return cancelLockedOrder(order);
    }

    // 管理者向け注文一覧取得
    @Transactional(readOnly = true)
    public List<Order> findAll() {

        return orderRepository.findAll().stream().map(this::responseReady).toList();
    }

    // 注文詳細取得
    @Transactional(readOnly = true)
    public Order findById(Long id) {

        return responseReady(orderRepository.findById(id)
                .orElseThrow(OrderNotFoundException::new));
    }

    // 売上集計
    public SalesResponse getSales() {
        return getSales(null, null);
    }

    @Transactional(readOnly = true)
    public SalesResponse getSales(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidOrderRequestException("売上期間のfromはtoより前の日時を指定してください");
        }
        return orderRepository.aggregateSales(from, to);
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
        return responseReady(orderRepository.save(order));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order completeOrder(Long id) {
        Order order = findByIdForUpdate(id);
        // DATETIME(6)とAPIの完了日時を同じ精度に揃える。
        order.complete(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return responseReady(orderRepository.save(order));
    }

    // 注文キャンセル
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order cancelOrder(Long id) {
        return cancelLockedOrder(findByIdForUpdate(id));
    }

    // self/adminの両方で、取得済みの注文ロック下で既存の復元処理を使う
    private Order cancelLockedOrder(Order order) {
        order.validateTransitionTo(OrderStatus.CANCELLED);
        User authenticatedActor = currentUser.requireUser();
        // 手動調整のUser -> Productと整合させる。actor FKのINSERT時ロックをProduct後に待たない。
        User actor = userRepository.findByIdForUpdate(authenticatedActor.getId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証ユーザーが存在しません"));

        // Order -> User(actor) -> ProductのID昇順。先にロードされた商品もrefreshしてから計算する
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
            int before = entry.getKey().getStock();
            int quantity = entry.getValue() - before;
            entry.getKey().increaseStock(quantity);
            productRepository.save(entry.getKey());
            inventoryService.recordOrderChange(entry.getKey(), before, quantity, order,
                    InventoryMovementReason.ORDER_CANCELLED, actor);
        }
        order.cancel();
        return responseReady(orderRepository.save(order));
    }

    private Order findByIdForUpdate(Long id) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow(OrderNotFoundException::new);
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    // Transaction内でDTOに必要な関連を初期化し、OSIVに依存しないレスポンスにする
    private Order responseReady(Order order) {
        order.getCustomer().getUsername();
        order.getOrderItems().size();
        return order;
    }

    // 同期HTTP出力中だけread-only snapshot transactionを保持する。CSV全量は保持しない。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = IOException.class)
    public void exportCsv(OutputStream output) throws IOException {
        // 最初のDB失敗ではまだresponseをcommitせず、既存ProblemDetailを返せる。
        List<OrderCsvRow> batch = orderItemRepository.findCsvBatch(0, 0, CSV_BATCH_SIZE);
        var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 8192);
        OrderCsvWriter.header(writer);
        while (!batch.isEmpty()) {
            for (OrderCsvRow row : batch) OrderCsvWriter.row(writer, row);
            writer.flush(); // 次のDB取得より前に送信する。切断時はここで中断する。
            if (batch.size() < CSV_BATCH_SIZE) break;
            long nextOrderId = batch.get(batch.size() - 1).orderId();
            long nextItemId = batch.get(batch.size() - 1).itemId();
            // 前バッチを参照から外してから次を取得する（同時に2バッチを保持しない）。
            batch = null;
            batch = orderItemRepository.findCsvBatch(nextOrderId, nextItemId, CSV_BATCH_SIZE);
        }
        writer.flush();
        // HTTP streamはServlet所有。closeせず、失敗時もTx interceptorがconnectionを解放する。
    }
}
