package com.example.demo.repository;

import com.example.demo.dto.OrderRequest;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.service.OrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"})
@Import({com.example.demo.config.ClockConfiguration.class, OrderService.class, com.example.demo.service.InventoryService.class, com.example.demo.security.CurrentUser.class})
class OrderDataIntegrityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (1, 'alice', 'test-hash', 'ROLE_USER')");
        com.example.demo.TestOrders.authenticate();
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1, 'apple', 100, 10)");
        jdbc.update("INSERT INTO orders (id, total_price, status, customer_id, ordered_at, updated_at, completed_at) VALUES (1000, 25, 'COMPLETED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(1L, null), Arguments.of(1L, 0), Arguments.of(1L, -1),
                Arguments.of(null, 1), Arguments.of(0L, 1), Arguments.of(-1L, 1));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 不正注文で在庫と注文と明細と既存売上は変化しない(Long productId, Integer quantity) {
        OrderRequest request = com.example.demo.TestOrders.request(productId, quantity);

        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));

        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 正常な未完了注文は在庫と明細を更新し売上には計上しない(int quantity) {
        OrderRequest request = com.example.demo.TestOrders.request(1L, quantity);

        orderService.createOrder(request, com.example.demo.TestOrders.key());
        entityManager.flush();

        assertEquals(10 - quantity, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(quantity, jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }

    @Test
    void 金額上限超過で在庫と注文と明細と既存売上は変化しない() {
        jdbc.update("UPDATE products SET price = ?, stock = 10 WHERE id = 1", Long.MAX_VALUE);
        OrderRequest request = com.example.demo.TestOrders.request(1L, 2);

        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));

        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 生SQLの不正在庫INSERTとUPDATEを拒否(Integer stock) {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO products (name, price, stock) VALUES ('invalid', 100, ?)", stock));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE products SET stock = ? WHERE id = 1", stock));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 生SQLの不正数量INSERTとUPDATEを拒否(Integer quantity) {
        jdbc.update("INSERT INTO order_items (id, quantity, price, product_name_snapshot, order_id, product_id) VALUES (1000, 1, 100, 'apple', 1000, 1)");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO order_items (quantity, price, product_name_snapshot, order_id, product_id) VALUES (?, 100, 'apple', 1000, 1)", quantity));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE order_items SET quantity = ? WHERE id = 1000", quantity));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 1000", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }
}
