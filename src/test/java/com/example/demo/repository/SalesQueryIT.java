package com.example.demo.repository;

import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 現行のSUM(totalPrice)を固定する。CANCELLED除外などの仕様変更は将来のsales Issueで扱う。
@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderService.class, CurrentUser.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SalesQueryIT {
    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("sales_query");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
    }

    @Autowired private UserRepository users;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OrderService service;
    private User customer;

    @BeforeEach
    void fixtures() {
        // DataJpaTestのTransaction rollbackで、明細・注文・商品・ユーザーを毎回破棄する。
        assertEquals(0, orders.count());
        customer = users.saveAndFlush(User.builder().username("sales-user")
                .password("test-hash").role("ROLE_USER").build());
    }

    @Test
    void noOrdersReturnsZeroSalesAndZeroOrders() {
        assertSales(0, 0);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void currentSemanticsCountEveryStatusIncludingCancelled(OrderStatus status) {
        saveOrder(120, 3, status);
        assertSales(360, 1);
    }

    @Test
    void multipleOrdersSumPersistedTotalsAndCountOrdersRatherThanItems() {
        Product first = products.saveAndFlush(Product.builder().name("apple").price(100).stock(10).build());
        Product second = products.saveAndFlush(Product.builder().name("orange").price(250).stock(10).build());
        orders.saveAndFlush(Order.create(customer,
                List.of(OrderItem.create(first, 2), OrderItem.create(second, 3))));
        saveOrder(50, 4, OrderStatus.PENDING);
        assertSales(1150, 2);
    }

    @Test
    void currentSemanticsIncludeCancelledAlongsidePendingShippedAndCompleted() {
        saveOrder(100, 1, OrderStatus.PENDING);
        saveOrder(200, 2, OrderStatus.SHIPPED);
        saveOrder(300, 3, OrderStatus.COMPLETED);
        saveOrder(400, 4, OrderStatus.CANCELLED);
        assertSales(3000, 4);
    }

    @Test
    void zeroPriceOrderStillIncreasesTotalOrders() {
        saveOrder(0, 2, OrderStatus.COMPLETED);
        assertSales(0, 1);
    }

    private void saveOrder(int price, int quantity, OrderStatus status) {
        Product product = products.saveAndFlush(Product.builder()
                .name("sales-product").price(price).stock(20).build());
        Order order = Order.create(customer, List.of(OrderItem.create(product, quantity)));
        switch (status) {
            case SHIPPED -> order.ship();
            case COMPLETED -> { order.ship(); order.complete(); }
            case CANCELLED -> order.cancel();
            case PENDING -> { }
        }
        orders.saveAndFlush(order);
    }

    private void assertSales(int totalSales, long totalOrders) {
        // 実Service -> 実Repository -> MariaDB query。mockの集計値では検証しない。
        SalesResponse response = service.getSales();
        assertEquals(totalSales, response.getTotalSales());
        assertEquals(totalOrders, response.getTotalOrders());
        assertEquals(totalSales, orders.getTotalSales());
        assertEquals(totalOrders, orders.count());
    }
}
