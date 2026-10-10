package com.example.demo.repository;

import com.example.demo.controller.AdminOrderController;
import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.exception.OrderInventoryRestorationException;
import com.example.demo.exception.GlobalExceptionHandler;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({OrderService.class, com.example.demo.security.CurrentUser.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderCancellationTransactionTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @SpyBean
    private OrderRepository orderRepository;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO users (id, username, password, role) VALUES (1, 'alice', 'test-hash', 'ROLE_USER')");
        com.example.demo.TestOrders.authenticate();
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1, 'apple', 100, 10)");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private Order createFiveItemOrder() {
        OrderRequest request = com.example.demo.TestOrders.request(1L, 5);
        return orderService.createOrder(request);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @Test
    void stockTenOrderFiveCancelTwiceNeverBecomesFifteen() {
        Order order = createFiveItemOrder();
        assertEquals(5, stock(1L));

        orderService.cancelOrder(order.getId());
        assertEquals(10, stock(1L));
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.cancelOrder(order.getId()));
        assertThrows(IllegalOrderStateTransitionException.class,
                () -> orderService.updateStatus(order.getId(), OrderStatus.PENDING));

        assertEquals(10, stock(1L));
        assertEquals("CANCELLED", orderStatus(order.getId()));
    }

    @Test
    void realControllerRejectsCancelPendingCancelCycleWith409() throws Exception {
        Order order = createFiveItemOrder();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminOrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/v1/admin/orders/{id}/cancel", order.getId())).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/orders/{id}/status", order.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/orders/{id}/cancel", order.getId())).andExpect(status().isConflict());

        assertEquals(10, stock(1L));
        assertEquals("CANCELLED", orderStatus(order.getId()));
    }

    @Test
    void legacyStatusCancellationRestoresStockExactlyOnce() {
        Order order = createFiveItemOrder();

        orderService.updateStatus(order.getId(), OrderStatus.CANCELLED);
        assertThrows(IllegalOrderStateTransitionException.class,
                () -> orderService.updateStatus(order.getId(), OrderStatus.CANCELLED));

        assertEquals(10, stock(1L));
        assertEquals("CANCELLED", orderStatus(order.getId()));
    }

    @Test
    void shipAndCompleteDoNotRestoreStockAndCannotBeCancelled() {
        Order order = createFiveItemOrder();

        orderService.shipOrder(order.getId());
        assertEquals(5, stock(1L));
        assertEquals("SHIPPED", orderStatus(order.getId()));
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.cancelOrder(order.getId()));
        orderService.completeOrder(order.getId());
        assertThrows(IllegalOrderStateTransitionException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(5, stock(1L));
        assertEquals("COMPLETED", orderStatus(order.getId()));
    }

    @Test
    void overflowLeavesOrderPendingAndStockUnchanged() {
        Order order = createFiveItemOrder();
        jdbc.update("UPDATE products SET stock = ? WHERE id = 1", Integer.MAX_VALUE);

        assertThrows(OrderInventoryRestorationException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(Integer.MAX_VALUE, stock(1L));
        assertEquals("PENDING", orderStatus(order.getId()));
    }

    @Test
    void laterLineOverflowDoesNotRestoreEarlierProduct() {
        Order order = createFiveItemOrder();
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (2, 'orange', 100, ?)", Integer.MAX_VALUE);
        jdbc.update("INSERT INTO order_items (quantity, price, product_name_snapshot, order_id, product_id) VALUES (1, 100, 'orange', ?, 2)", order.getId());

        assertThrows(OrderInventoryRestorationException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(5, stock(1L));
        assertEquals(Integer.MAX_VALUE, stock(2L));
        assertEquals("PENDING", orderStatus(order.getId()));
    }

    @Test
    void persistenceFailureAfterStockFlushRollsBackAllProductsAndOrder() {
        Order order = createFiveItemOrder();
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (2, 'orange', 100, 7)");
        jdbc.update("INSERT INTO order_items (quantity, price, product_name_snapshot, order_id, product_id) VALUES (3, 100, 'orange', ?, 2)", order.getId());
        AtomicInteger stockSeenInsideFailedTransaction = new AtomicInteger();
        doAnswer(invocation -> {
            // SQLを実際に発行してから失敗させ、テスト自体のrollbackに頼らず確認する
            entityManager.flush();
            stockSeenInsideFailedTransaction.set(stock(1L));
            throw new DataIntegrityViolationException("simulated persistence failure");
        }).when(orderRepository).save(argThat(savedOrder -> savedOrder != null && order.getId().equals(savedOrder.getId())));

        assertThrows(DataIntegrityViolationException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(10, stockSeenInsideFailedTransaction.get());
        assertEquals(5, stock(1L));
        assertEquals(7, stock(2L));
        assertEquals("PENDING", orderStatus(order.getId()));
    }
}
