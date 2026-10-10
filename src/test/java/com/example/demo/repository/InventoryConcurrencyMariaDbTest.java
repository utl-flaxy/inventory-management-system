package com.example.demo.repository;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductVersionConflictException;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.service.OrderService;
import com.example.demo.service.ProductService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderService.class, ProductService.class, com.example.demo.security.CurrentUser.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Timeout(120)
class InventoryConcurrencyMariaDbTest {
    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("inventory_concurrency")
            .withCommand("--innodb-lock-wait-timeout=10", "--max-connections=160")
            .withStartupTimeout(Duration.ofSeconds(90));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        // 100 worker全員が接続・Transactionを保持してから同期開始できる容量
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 110);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 2);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 30_000);
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductService productService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void customer() {
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (1, 'alice', 'test-hash', 'ROLE_USER')");
        com.example.demo.TestOrders.authenticate();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void product(long id, int stock) {
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (?, 'apple', 100, ?)", id, stock);
    }

    private Order order(int quantity) {
        OrderRequest request = com.example.demo.TestOrders.request(1L, quantity);
        return orderService.createOrder(request);
    }

    private ProductUpdateRequest updateRequest(long version) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setName("orange");
        request.setPrice(200);
        request.setStock(20);
        request.setVersion(version);
        return request;
    }

    private int stock(long id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    private record Outcome(Object value, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("同期ポイントがtimeoutしました");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("workerが中断されました", ex);
        }
    }

    private List<Outcome> concurrently(List<Supplier<?>> operations) throws Exception {
        CountDownLatch connected = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> connectionIds = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (Supplier<?> operation : operations) {
                futures.add(executor.submit(() -> {
                    com.example.demo.TestOrders.authenticate();
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    transaction.setTimeout(90);
                    try {
                        Object value = transaction.execute(status -> {
                            connectionIds.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            connected.countDown();
                            await(start);
                            return operation.get();
                        });
                        return new Outcome(value, null);
                    } catch (RuntimeException ex) {
                        return new Outcome(null, ex);
                    } finally {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(connected.await(60, TimeUnit.SECONDS), "全workerの独立Transactionが開始されること");
            assertEquals(operations.size(), connectionIds.size(), "全workerが異なるDB接続を保持すること");
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "全workerが終了すること");
        }
    }

    private List<Outcome> concurrently(int workers, Supplier<?> operation) throws Exception {
        return concurrently(IntStream.range(0, workers).<Supplier<?>>mapToObj(i -> operation).toList());
    }

    private void successes(List<Outcome> outcomes, int expected) {
        assertEquals(expected, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
    }

    @ParameterizedTest
    @CsvSource({"1,20,1", "10,20,10", "100,100,100"})
    void concurrentOrdersNeverOversellOrLoseUpdates(int initialStock, int workers, int expectedSuccesses) throws Exception {
        product(1L, initialStock);

        List<Outcome> outcomes = concurrently(workers, () -> order(1));

        successes(outcomes, expectedSuccesses);
        outcomes.stream().filter(outcome -> !outcome.succeeded())
                .forEach(outcome -> assertInstanceOf(OutOfStockException.class, outcome.failure()));
        assertEquals(0, stock(1L));
        assertEquals(expectedSuccesses, count("orders"));
        assertEquals(expectedSuccesses, count("order_items"));
        assertEquals(expectedSuccesses, jdbc.queryForObject("SELECT SUM(quantity) FROM order_items", Integer.class));
        assertEquals(expectedSuccesses, jdbc.queryForObject("SELECT version FROM products WHERE id = 1", Long.class));
    }

    @Test
    void twentyConcurrentCancellationsRestoreExactlyOnceIncludingLegacyApi() throws Exception {
        product(1L, 10);
        Order order = order(5);
        assertEquals(5, stock(1L));
        List<Supplier<?>> operations = IntStream.range(0, 20).<Supplier<?>>mapToObj(i -> i % 2 == 0
                ? () -> orderService.cancelOrder(order.getId())
                : () -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED)).toList();

        List<Outcome> outcomes = concurrently(operations);

        successes(outcomes, 1);
        outcomes.stream().filter(outcome -> !outcome.succeeded())
                .forEach(outcome -> assertInstanceOf(IllegalOrderStateTransitionException.class, outcome.failure()));
        assertEquals(10, stock(1L));
        assertEquals("CANCELLED", orderStatus(order.getId()));
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM products WHERE id = 1", Long.class));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
    }

    @RepeatedTest(5)
    void cancelAndShipCannotBothSucceed() throws Exception {
        product(1L, 10);
        Order order = order(5);

        List<Outcome> outcomes = concurrently(List.of(() -> orderService.cancelOrder(order.getId()),
                () -> orderService.shipOrder(order.getId())));

        successes(outcomes, 1);
        outcomes.stream().filter(outcome -> !outcome.succeeded())
                .forEach(outcome -> assertInstanceOf(IllegalOrderStateTransitionException.class, outcome.failure()));
        String status = orderStatus(order.getId());
        assertTrue(status.equals("CANCELLED") || status.equals("SHIPPED"));
        assertEquals(status.equals("CANCELLED") ? 10 : 5, stock(1L));
    }

    @Test
    void cancelCannotRaceCompletionOfShippedOrder() throws Exception {
        product(1L, 10);
        Order order = order(5);
        orderService.shipOrder(order.getId());

        List<Outcome> outcomes = concurrently(List.of(() -> orderService.cancelOrder(order.getId()),
                () -> orderService.completeOrder(order.getId())));

        assertInstanceOf(IllegalOrderStateTransitionException.class, outcomes.get(0).failure());
        assertTrue(outcomes.get(1).succeeded(), outcomes.toString());
        assertEquals("COMPLETED", orderStatus(order.getId()));
        assertEquals(5, stock(1L));
    }

    @Test
    void productUpdateAndOrderDoNotSilentlyOverwriteStockOrMixPrices() throws Exception {
        product(1L, 10);

        List<Outcome> outcomes = concurrently(List.of(() -> productService.update(1L, updateRequest(0L)),
                () -> order(1)));

        assertTrue(outcomes.get(1).succeeded(), outcomes.toString());
        boolean updated = outcomes.get(0).succeeded();
        if (!updated) {
            assertTrue(outcomes.get(0).failure() instanceof ProductVersionConflictException
                    || outcomes.get(0).failure() instanceof OptimisticLockingFailureException, outcomes.toString());
        }
        assertEquals(updated ? 19 : 9, stock(1L));
        assertEquals(updated ? 200 : 100, jdbc.queryForObject("SELECT price FROM products WHERE id = 1", Integer.class));
        assertEquals(updated ? 200 : 100, jdbc.queryForObject("SELECT price FROM order_items", Integer.class));
        assertEquals(updated ? 200 : 100, jdbc.queryForObject("SELECT total_price FROM orders", Integer.class));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
    }

    @Test
    void optimisticVersionRejectsUpdateThatReadBeforeOrderCommitted() throws Exception {
        product(1L, 10);
        CountDownLatch staleProductLoaded = new CountDownLatch(1);
        CountDownLatch orderCreated = new CountDownLatch(1);

        List<Outcome> outcomes = concurrently(List.of(() -> {
            productRepository.findById(1L).orElseThrow();
            staleProductLoaded.countDown();
            await(orderCreated);
            return productService.update(1L, updateRequest(0L));
        }, () -> {
            await(staleProductLoaded);
            Order order = order(1);
            orderCreated.countDown();
            return order;
        }));

        assertInstanceOf(OptimisticLockingFailureException.class, outcomes.get(0).failure());
        assertTrue(outcomes.get(1).succeeded(), outcomes.toString());
        assertEquals(9, stock(1L));
        assertEquals(100, jdbc.queryForObject("SELECT price FROM products WHERE id = 1", Integer.class));
        assertEquals(1, count("orders"));
    }

    @Test
    void oldClientVersionIsRejectedEvenIfUpdateStartsAfterOrderCommitted() {
        product(1L, 10);
        var displayedProduct = productService.findById(1L);
        order(1);

        assertThrows(ProductVersionConflictException.class,
                () -> productService.update(1L, updateRequest(displayedProduct.getVersion())));

        assertEquals(9, stock(1L));
        assertEquals(1L, productService.findById(1L).getVersion());
    }

    @Test
    void lockedOrderPriceUsesCurrentProductAfterAdministratorUpdateDespiteEarlierPriceRead() throws Exception {
        product(1L, 10);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch productUpdated = new CountDownLatch(1);

        List<Outcome> outcomes = concurrently(List.of(() -> {
            await(snapshotRead);
            var response = productService.update(1L, updateRequest(0L));
            productUpdated.countDown();
            return response;
        }, () -> {
            assertEquals(100, jdbc.queryForObject("SELECT price FROM products WHERE id = 1", Integer.class));
            snapshotRead.countDown();
            await(productUpdated);
            return order(1);
        }));

        successes(outcomes, 2);
        assertEquals(19, stock(1L));
        assertEquals(200, jdbc.queryForObject("SELECT price FROM order_items", Integer.class));
        assertEquals(200, jdbc.queryForObject("SELECT total_price FROM orders", Integer.class));
        assertEquals(2L, productService.findById(1L).getVersion());
    }

    @Test
    void cancellationRefreshesProductLoadedBeforeConcurrentOrder() throws Exception {
        product(1L, 10);
        Order existing = order(5);
        CountDownLatch oldProductLoaded = new CountDownLatch(1);
        CountDownLatch newOrderCreated = new CountDownLatch(1);

        List<Outcome> outcomes = concurrently(List.of(() -> {
            Order cached = orderRepository.findById(existing.getId()).orElseThrow();
            assertEquals(5, cached.getOrderItems().get(0).getProduct().getStock());
            oldProductLoaded.countDown();
            await(newOrderCreated);
            return orderService.cancelOrder(existing.getId());
        }, () -> {
            await(oldProductLoaded);
            Order newOrder = order(1);
            newOrderCreated.countDown();
            return newOrder;
        }));

        successes(outcomes, 2);
        assertEquals(9, stock(1L));
        assertEquals("CANCELLED", orderStatus(existing.getId()));
        assertEquals(2, count("orders"));
        assertEquals(1, jdbc.queryForObject("SELECT SUM(i.quantity) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.status <> 'CANCELLED'", Integer.class));
    }

    @Test
    void differentOrdersWithReverseProductLinesCancelWithoutDeadlockOrLostRestoration() throws Exception {
        product(1L, 6);
        product(2L, 6);
        jdbc.update("INSERT INTO orders (id, total_price, status, customer_id, ordered_at, updated_at) VALUES (1001, 400, 'PENDING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), (1002, 400, 'PENDING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO order_items (quantity, price, product_name_snapshot, order_id, product_id) VALUES (2,100,'apple',1001,1), (2,100,'apple',1001,2), (2,100,'apple',1002,2), (2,100,'apple',1002,1)");

        List<Outcome> outcomes = concurrently(List.of(() -> orderService.cancelOrder(1001L),
                () -> orderService.cancelOrder(1002L)));

        successes(outcomes, 2);
        assertEquals(10, stock(1L));
        assertEquals(10, stock(2L));
        assertEquals("CANCELLED", orderStatus(1001L));
        assertEquals("CANCELLED", orderStatus(1002L));
    }

    @Test
    void concurrentProductUpdatesUsingSameClientVersionHaveOnlyOneWinner() throws Exception {
        product(1L, 10);

        List<Outcome> outcomes = concurrently(20, () -> productService.update(1L, updateRequest(0L)));

        successes(outcomes, 1);
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertTrue(outcome.failure() instanceof ProductVersionConflictException
                        || outcome.failure() instanceof OptimisticLockingFailureException, outcomes.toString()));
        assertEquals(20, stock(1L));
        assertEquals(1L, productService.findById(1L).getVersion());
    }

    @Test
    void versionDdlBackfillsExistingRowsWithoutChangingStockAndCanBeAppliedTwice() throws Exception {
        jdbc.execute("CREATE TABLE issue12_existing_products (id BIGINT PRIMARY KEY, stock INT NOT NULL)");
        try {
            jdbc.update("INSERT INTO issue12_existing_products VALUES (1, 10), (2, 0)");
            String sql = Files.readString(Path.of("docs/db/issue-12-product-version.sql"))
                    .replaceAll("\\bproducts\\b", "issue12_existing_products");
            var script = new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            script.execute(jdbc.getDataSource());
            script.execute(jdbc.getDataSource());

            assertEquals(10, jdbc.queryForObject("SELECT stock FROM issue12_existing_products WHERE id = 1", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT stock FROM issue12_existing_products WHERE id = 2", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM issue12_existing_products WHERE version = 0", Integer.class));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE issue12_existing_products SET version = NULL WHERE id = 1"));
        } finally {
            jdbc.execute("DROP TABLE issue12_existing_products");
        }
    }

    @Test
    void orderRefreshDoesNotImplicitlyLockProductsBeforeExplicitInventoryLocking() throws Exception {
        product(1L, 10);
        Order existing = order(5);
        CountDownLatch orderLocked = new CountDownLatch(1);
        CountDownLatch productUpdated = new CountDownLatch(1);

        List<Outcome> outcomes = concurrently(List.of(() -> {
            Order locked = orderRepository.findByIdForUpdate(existing.getId()).orElseThrow();
            locked.getOrderItems().get(0).getProduct().getStock();
            entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
            orderLocked.countDown();
            await(productUpdated);
            return orderService.cancelOrder(existing.getId());
        }, () -> {
            await(orderLocked);
            var response = productService.update(1L, updateRequest(1L));
            productUpdated.countDown();
            return response;
        }));

        successes(outcomes, 2);
        assertEquals(25, stock(1L));
        assertEquals("CANCELLED", orderStatus(existing.getId()));
        assertEquals(3L, productService.findById(1L).getVersion());
    }

    @RepeatedTest(3)
    void reverseInputMultiProductOrdersCommitWithoutDeadlockAndCancelExactlyOnce() throws Exception {
        product(1L, 10);
        product(2L, 10);
        List<Outcome> outcomes = concurrently(List.of(
                () -> orderService.createOrder(com.example.demo.TestOrders.request(
                        new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 3))),
                () -> orderService.createOrder(com.example.demo.TestOrders.request(
                        new OrderItemRequest(2L, 3), new OrderItemRequest(1L, 2)))));

        successes(outcomes, 2);
        assertEquals(6, stock(1L));
        assertEquals(4, stock(2L));
        assertEquals(2, count("orders"));
        assertEquals(4, count("order_items"));
        assertEquals(1000, jdbc.queryForObject("SELECT SUM(total_price) FROM orders", Integer.class));
        List<Outcome> cancellations = concurrently(outcomes.stream().<Supplier<?>>map(outcome ->
                () -> orderService.cancelOrder(((Order) outcome.value()).getId())).toList());
        successes(cancellations, 2);
        assertEquals(10, stock(1L));
        assertEquals(10, stock(2L));
        for (Outcome outcome : outcomes) {
            long id = ((Order) outcome.value()).getId();
            assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.cancelOrder(id));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, id));
        }
        assertEquals(10, stock(1L));
        assertEquals(10, stock(2L));
    }

    @Test
    void mariaDbMultiProductShortageAndOverflowLeaveNoPartialOrder() {
        product(1L, 10);
        product(2L, 0);
        assertThrows(OutOfStockException.class, () -> orderService.createOrder(com.example.demo.TestOrders.request(
                new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1))));
        assertEquals(10, stock(1L));
        assertEquals(0, stock(2L));
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));

        jdbc.update("UPDATE products SET price = ?, stock = 10 WHERE id = 1", Integer.MAX_VALUE);
        jdbc.update("UPDATE products SET stock = 10 WHERE id = 2");
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(com.example.demo.TestOrders.request(
                new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1))));
        assertEquals(10, stock(1L));
        assertEquals(10, stock(2L));
        assertEquals(0, count("orders"));
        assertEquals(0, count("order_items"));
    }

    @Test
    void mariaDbSnapshotsAndCustomerSurviveProductUpdates() {
        product(1L, 10);
        product(2L, 5);
        jdbc.update("UPDATE products SET name = 'orange', price = 300 WHERE id = 2");
        Order order = orderService.createOrder(com.example.demo.TestOrders.request(
                new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1)));
        productService.update(1L, updateRequest(1L));
        OrderResponse response = OrderResponse.from(orderService.findById(order.getId()));
        assertEquals("alice", response.customer().username());
        assertEquals("apple", response.items().get(0).productName());
        assertEquals(100, response.items().get(0).unitPrice());
        assertEquals(500, response.totalPrice());
        assertEquals(1L, response.customer().id());
        assertNotNull(response.orderedAt());
        assertNotNull(response.updatedAt());
    }

    private void migrationSchema() {
        jdbc.execute("CREATE TABLE issue14_users (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE issue14_products (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT, stock INT NOT NULL)");
        jdbc.execute("CREATE TABLE issue14_orders (id BIGINT PRIMARY KEY, total_price INT, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE issue14_order_items (id BIGINT PRIMARY KEY, quantity INT NOT NULL, price INT, order_id BIGINT, product_id BIGINT)");
        jdbc.update("INSERT INTO issue14_users VALUES (1)");
        jdbc.update("INSERT INTO issue14_products VALUES (1, 'current-name', 900, 8)");
        jdbc.update("INSERT INTO issue14_orders VALUES (1, 200, 'PENDING')");
        jdbc.update("INSERT INTO issue14_order_items VALUES (1, 2, 100, 1, 1)");
    }

    private void migrationScript(String phase) throws Exception {
        String sql = Files.readString(Path.of("docs/db/issue-14-order-aggregate-" + phase + ".sql"))
                .replace("fk_orders_customer", "fk_issue14_orders_customer");
        for (String table : List.of("order_items", "orders", "products", "users")) {
            sql = sql.replaceAll("\\b" + table + "\\b", "issue14_" + table);
        }
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                .execute(jdbc.getDataSource());
    }

    private void confirmedMigrationMetadata() {
        jdbc.update("UPDATE issue14_orders SET customer_id = 1, ordered_at = '2000-01-01 00:00:00', updated_at = '2001-01-01 00:00:00'");
        jdbc.update("UPDATE issue14_order_items SET product_name_snapshot = 'historical-name'");
    }

    private void dropMigrationSchema() {
        jdbc.execute("DROP TABLE IF EXISTS issue14_order_items");
        jdbc.execute("DROP TABLE IF EXISTS issue14_orders");
        jdbc.execute("DROP TABLE IF EXISTS issue14_products");
        jdbc.execute("DROP TABLE IF EXISTS issue14_users");
    }

    @Test
    void aggregateDdlRequiresConfirmedMetadataAndPreservesExistingFinancialAndInventoryData() throws Exception {
        migrationSchema();
        try {
            migrationScript("expand");
            migrationScript("expand");
            assertNull(jdbc.queryForObject("SELECT customer_id FROM issue14_orders", Long.class));
            assertNull(jdbc.queryForObject("SELECT ordered_at FROM issue14_orders", java.sql.Timestamp.class));
            assertNull(jdbc.queryForObject("SELECT product_name_snapshot FROM issue14_order_items", String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT version FROM issue14_orders", Long.class));
            assertThrows(RuntimeException.class, () -> migrationScript("constrain"));

            confirmedMigrationMetadata();
            migrationScript("constrain");
            assertEquals(8, jdbc.queryForObject("SELECT stock FROM issue14_products", Integer.class));
            assertEquals(200, jdbc.queryForObject("SELECT total_price FROM issue14_orders", Integer.class));
            assertEquals(100, jdbc.queryForObject("SELECT price FROM issue14_order_items", Integer.class));
            assertEquals("historical-name", jdbc.queryForObject("SELECT product_name_snapshot FROM issue14_order_items", String.class));
            for (String column : List.of("customer_id", "ordered_at", "updated_at", "version", "total_price")) {
                assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE issue14_orders SET " + column + " = NULL"));
            }
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE issue14_orders SET customer_id = 999"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE issue14_order_items SET product_name_snapshot = NULL"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE issue14_order_items SET price = NULL"));
        } finally {
            dropMigrationSchema();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "total-mismatch", "orphan-item", "missing-snapshot", "missing-customer", "missing-ordered-at"})
    void aggregateDdlPreflightRejectsIncompleteOrInconsistentHistoryBeforeAddingConstraints(String problem) throws Exception {
        migrationSchema();
        try {
            migrationScript("expand");
            confirmedMigrationMetadata();
            switch (problem) {
                case "empty" -> jdbc.update("DELETE FROM issue14_order_items");
                case "total-mismatch" -> jdbc.update("UPDATE issue14_orders SET total_price = 201");
                case "orphan-item" -> jdbc.update("UPDATE issue14_order_items SET product_id = 999");
                case "missing-snapshot" -> jdbc.update("UPDATE issue14_order_items SET product_name_snapshot = NULL");
                case "missing-customer" -> jdbc.update("UPDATE issue14_orders SET customer_id = 999");
                case "missing-ordered-at" -> jdbc.update("UPDATE issue14_orders SET ordered_at = NULL");
            }
            assertThrows(RuntimeException.class, () -> migrationScript("constrain"));
            assertEquals("YES", jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'issue14_orders' AND COLUMN_NAME = 'customer_id'", String.class));
            assertEquals(8, jdbc.queryForObject("SELECT stock FROM issue14_products", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM issue14_orders", Integer.class));
        } finally {
            dropMigrationSchema();
        }
    }

    @RepeatedTest(5)
    void ownerCancellationAndAdministratorShipmentCannotBothSucceed() throws Exception {
        product(1L, 10);
        Order order = order(5);
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (3,'admin','test-hash','ROLE_ADMIN')");

        List<Outcome> outcomes = concurrently(List.of(() -> orderService.cancelMyOrder(order.getId()),
                () -> {
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null,
                                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                    return orderService.shipOrder(order.getId());
                }));

        successes(outcomes, 1);
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertInstanceOf(IllegalOrderStateTransitionException.class, outcome.failure()));
        String status = orderStatus(order.getId());
        assertTrue(status.equals("CANCELLED") || status.equals("SHIPPED"));
        assertEquals(status.equals("CANCELLED") ? 10 : 5, stock(1L));
        assertEquals(1L, jdbc.queryForObject("SELECT customer_id FROM orders WHERE id = ?", Long.class, order.getId()));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order.getId()));
    }

    @Test
    void foreignCustomerCannotCancelEvenWhenTheOrderExists() {
        product(1L, 10);
        Order order = order(5);
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (2,'bob','test-hash','ROLE_USER')");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bob", null, List.of()));

        assertThrows(com.example.demo.exception.OrderNotFoundException.class, () -> orderService.cancelMyOrder(order.getId()));
        assertEquals(5, stock(1L));
        assertEquals("PENDING", orderStatus(order.getId()));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
    }
}
