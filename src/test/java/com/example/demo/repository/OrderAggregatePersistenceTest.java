package com.example.demo.repository;

import com.example.demo.controller.SelfOrderController;
import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.entity.Order;
import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.OrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.demo.TestOrders.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"})
@Import({com.example.demo.config.ClockConfiguration.class, OrderService.class, CurrentUser.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderAggregatePersistenceTest {
    @Autowired
    private OrderService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @SpyBean
    private OrderRepository orders;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (1, 'alice', 'test-hash', 'ROLE_USER'), (2, 'bob', 'test-hash', 'ROLE_USER')");
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1, 'apple', 100, 10), (2, 'orange', 300, 5)");
        authenticate();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        SecurityContextHolder.clearContext();
    }

    private Order multiOrder() {
        return service.createOrder(request(new OrderItemRequest(2L, 1), new OrderItemRequest(1L, 2)));
    }

    private int stock(long id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private void noOrderAndUnchangedStocks(int secondStock) {
        assertEquals(10, stock(1));
        assertEquals(secondStock, stock(2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(0, orders.getTotalSales());
    }

    @Test
    void multiLineOrderPersistsCustomerSnapshotsTimestampsVersionAndCalculatedTotal() {
        Order order = multiOrder();
        assertEquals(8, stock(1));
        assertEquals(4, stock(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(500, order.getTotalPrice());
        assertEquals(500, jdbc.queryForObject("SELECT SUM(price * quantity) FROM order_items", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT customer_id FROM orders", Long.class));
        assertNotNull(order.getOrderedAt());
        assertNotNull(order.getUpdatedAt());
        assertEquals(0L, order.getVersion());

        // Transaction終了後にもDTO化でき、OSIVやEntityの秘密情報に依存しない
        OrderResponse response = OrderResponse.from(service.findById(order.getId()));
        assertEquals("alice", response.customer().username());
        assertEquals("apple", response.items().get(0).productName());
        assertEquals("orange", response.items().get(1).productName());
        assertEquals(100, response.items().get(0).unitPrice());
        assertEquals(300, response.items().get(1).unitPrice());
        assertEquals(500L, response.items().stream().mapToLong(item -> item.lineTotal()).sum());
        assertEquals(2, OrderResponse.from(service.findAll().get(0)).items().size());
    }

    @Test
    void shortageInLaterProductRollsBackTheEntireOrder() {
        jdbc.update("UPDATE products SET stock = 0 WHERE id = 2");
        assertThrows(OutOfStockException.class, this::multiOrder);
        noOrderAndUnchangedStocks(0);
    }

    @Test
    void missingLaterProductDoesNotCreateAnOrderOrReduceEarlierInventory() {
        assertThrows(InvalidOrderRequestException.class, () -> service.createOrder(
                request(new OrderItemRequest(1L, 2), new OrderItemRequest(999L, 1))));
        noOrderAndUnchangedStocks(5);
    }

    @Test
    void sumOverflowLeavesAllProductsAndSalesUnchanged() {
        jdbc.update("UPDATE products SET price = ? WHERE id = 1", Long.MAX_VALUE);
        assertThrows(InvalidOrderRequestException.class, () -> service.createOrder(
                request(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1))));
        noOrderAndUnchangedStocks(5);
    }

    @Test
    void failureAfterFlushingStocksOrderAndCascadedLinesRollsBackEverything() {
        AtomicInteger persistedLines = new AtomicInteger();
        AtomicInteger observedStock = new AtomicInteger();
        doAnswer(invocation -> {
            entityManager.persist(invocation.getArgument(0));
            entityManager.flush();
            persistedLines.set(jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
            observedStock.set(stock(1));
            throw new DataIntegrityViolationException("simulated failure after all inserts");
        }).when(orders).save(any(Order.class));
        assertThrows(DataIntegrityViolationException.class, this::multiOrder);
        assertEquals(2, persistedLines.get());
        assertEquals(8, observedStock.get());
        noOrderAndUnchangedStocks(5);
    }

    @Test
    void productEditsDoNotChangeHistoricalDetailListCsvOrTotal() throws Exception {
        Order order = multiOrder();
        jdbc.update("UPDATE products SET name = 'renamed', price = 999 WHERE id = 1");
        OrderResponse detail = OrderResponse.from(service.findById(order.getId()));
        OrderResponse listed = OrderResponse.from(service.findAll().get(0));
        assertEquals("apple", detail.items().get(0).productName());
        assertEquals(100, detail.items().get(0).unitPrice());
        assertEquals(500, detail.totalPrice());
        assertEquals(detail, listed);
        String csv = new String(service.exportCsv().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(csv.contains(order.getId() + ",apple,2,100,200,PENDING"), csv);
        assertTrue(csv.contains(order.getId() + ",orange,1,300,300,PENDING"), csv);
        assertFalse(csv.contains("renamed"));
    }

    @Test
    void duplicateProductReturns400WithoutAnySideEffect() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SelfOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2},{\"productId\":1,\"quantity\":3}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
        noOrderAndUnchangedStocks(5);
    }

    @Test
    void customerComesFromAuthenticationAndCannotBeChosenByRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SelfOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":2,\"customer\":{\"id\":2},\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customer.id").value(1))
                .andExpect(jsonPath("$.customer.username").value("alice"))
                .andExpect(jsonPath("$.customer.password").doesNotExist());
        assertEquals(1L, jdbc.queryForObject("SELECT customer_id FROM orders", Long.class));
    }

    @Test
    void missingAuthenticationReturns401WithoutChangingInventory() throws Exception {
        SecurityContextHolder.clearContext();
        var mvc = MockMvcBuilders.standaloneSetup(new SelfOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isUnauthorized());
        noOrderAndUnchangedStocks(5);
    }

    @Test
    void timestampsAndOrderVersionAdvanceWhileOrderedAtStaysFixed() {
        Order order = multiOrder();
        jdbc.update("UPDATE orders SET ordered_at = '2000-01-01 00:00:00', updated_at = '2000-01-01 00:00:00' WHERE id = ?", order.getId());
        Order before = service.findById(order.getId());
        Order shipped = service.shipOrder(order.getId());
        assertEquals(before.getOrderedAt(), shipped.getOrderedAt());
        assertTrue(shipped.getUpdatedAt().isAfter(before.getUpdatedAt()));
        assertEquals(1L, shipped.getVersion());
        assertEquals(2L, service.completeOrder(order.getId()).getVersion());
        assertEquals(8, stock(1));
        assertEquals(4, stock(2));
    }

    @Test
    void requiredCustomerAndTimestampsAndSnapshotAreEnforcedByDatabase() {
        Order order = multiOrder();
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders SET customer_id = NULL WHERE id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders SET customer_id = 999 WHERE id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders SET ordered_at = NULL WHERE id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders SET updated_at = NULL WHERE id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders SET version = NULL WHERE id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE order_items SET product_name_snapshot = NULL WHERE order_id = ?", order.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE order_items SET price = NULL WHERE order_id = ?", order.getId()));
    }
}
