package com.example.demo.repository;

import com.example.demo.dto.OrderRequest;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.service.OrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(OrderService.class)
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
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1, 'apple', 100, 10)");
        jdbc.update("INSERT INTO orders (id, total_price, status) VALUES (1000, 25, 'PENDING')");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(1L, null), Arguments.of(1L, 0), Arguments.of(1L, -1),
                Arguments.of(null, 1), Arguments.of(0L, 1), Arguments.of(-1L, 1));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 不正注文で在庫と注文と明細と既存売上は変化しない(Long productId, Integer quantity) {
        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);

        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request));

        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 正常注文は在庫と明細と売上を更新する(int quantity) {
        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);

        orderService.createOrder(request);
        entityManager.flush();

        assertEquals(10 - quantity, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(quantity, jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class));
        assertEquals(25 + 100 * quantity, orderRepository.getTotalSales());
    }

    @Test
    void 金額上限超過で在庫と注文と明細と既存売上は変化しない() {
        jdbc.update("UPDATE products SET price = 2, stock = ? WHERE id = 1", Integer.MAX_VALUE);
        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(Integer.MAX_VALUE / 2 + 1);

        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request));

        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
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
        jdbc.update("INSERT INTO order_items (id, quantity, price, order_id, product_id) VALUES (1000, 1, 100, 1000, 1)");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO order_items (quantity, price, order_id, product_id) VALUES (?, 100, 1000, 1)", quantity));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE order_items SET quantity = ? WHERE id = 1000", quantity));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 1000", Integer.class));
        assertEquals(25, orderRepository.getTotalSales());
    }
}
