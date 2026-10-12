package com.example.demo.repository;

import com.example.demo.dto.SalesResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.entity.User;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.OrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

// 売上・注文数は完了日時が判明したCOMPLETEDのみ。期間の境界も実MariaDBで検証する。
@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({com.example.demo.config.ClockConfiguration.class, OrderService.class, com.example.demo.service.InventoryService.class, CurrentUser.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SalesQueryIT {
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");
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
    @Autowired private EntityManager entityManager;
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
    void onlyCompletedOrdersContributeRevenueAndCount(OrderStatus status) {
        saveOrder(120, 3, status);
        assertSales(status == OrderStatus.COMPLETED ? 360 : 0, status == OrderStatus.COMPLETED ? 1 : 0);
    }

    @Test
    void multipleOrdersSumPersistedTotalsAndCountOrdersRatherThanItems() {
        Product first = products.saveAndFlush(Product.builder().name("apple").price(100L).stock(10).build());
        Product second = products.saveAndFlush(Product.builder().name("orange").price(250L).stock(10).build());
        Order multiItem = Order.create(customer, List.of(OrderItem.create(first, 2), OrderItem.create(second, 3)));
        multiItem.ship();
        multiItem.complete(MIDDLE);
        orders.saveAndFlush(multiItem);
        saveOrder(50, 4, OrderStatus.COMPLETED);
        assertSales(1150, 2);
    }

    @Test
    void mixedStatusesExcludeCancelledPendingAndShippedFromRevenueAndCount() {
        saveOrder(100, 1, OrderStatus.PENDING);
        saveOrder(200, 2, OrderStatus.SHIPPED);
        saveOrder(300, 3, OrderStatus.COMPLETED);
        saveOrder(400, 4, OrderStatus.CANCELLED);
        assertSales(900, 1);
    }

    @Test
    void zeroPriceOrderStillIncreasesTotalOrders() {
        saveOrder(0, 2, OrderStatus.COMPLETED);
        assertSales(0, 1);
    }

    private Order saveOrder(long price, int quantity, OrderStatus status) {
        return saveOrder(price, quantity, status, MIDDLE);
    }

    private Order saveOrder(long price, int quantity, OrderStatus status, Instant completedAt) {
        Product product = products.saveAndFlush(Product.builder()
                .name("sales-product").price(price).stock(20).build());
        Order order = Order.create(customer, List.of(OrderItem.create(product, quantity)));
        switch (status) {
            case SHIPPED -> order.ship();
            case COMPLETED -> { order.ship(); order.complete(completedAt); }
            case CANCELLED -> order.cancel();
            case PENDING -> { }
        }
        return orders.saveAndFlush(order);
    }

    private void assertSales(long totalSales, long totalOrders) {
        // 実Service -> 実Repository -> MariaDB query。mockの集計値では検証しない。
        SalesResponse response = service.getSales();
        assertEquals(totalSales, response.getTotalSales());
        assertEquals(totalOrders, response.getTotalOrders());
        assertEquals(totalSales, orders.getTotalSales());
        assertEquals(totalOrders, orders.aggregateSales(null, null).getTotalOrders());
        Object jpqlTotal = entityManager.createQuery("SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o WHERE o.status = com.example.demo.entity.OrderStatus.COMPLETED AND o.completedAt IS NOT NULL")
                .getSingleResult();
        assertInstanceOf(Long.class, jpqlTotal);
        assertEquals(totalSales, jpqlTotal);
    }

    @Test
    void highValueSalesSumIsLongAndExcludesCancelledAndPendingOrders() {
        saveOrder(3_000_000_000L, 1, OrderStatus.COMPLETED);
        saveOrder(4_000_000_000L, 1, OrderStatus.COMPLETED);
        saveOrder(5_000_000_000L, 1, OrderStatus.PENDING);
        saveOrder(6_000_000_000L, 1, OrderStatus.CANCELLED);
        assertSales(7_000_000_000L, 2);
        assertInstanceOf(Long.class, orders.getTotalSales());
    }

    @Test
    void sumsOfSmallPricesCanExceedIntegerMaximum() {
        saveOrder(2_000_000_000L, 1, OrderStatus.COMPLETED);
        saveOrder(2_000_000_000L, 1, OrderStatus.COMPLETED);
        assertSales(4_000_000_000L, 2);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "null,null,600,3", "2026-10-01T00:00:00Z,null,600,3",
            "null,2026-11-01T00:00:00Z,300,2",
            "2026-10-01T00:00:00Z,2026-11-01T00:00:00Z,300,2",
            "2026-11-01T00:00:00Z,null,300,1",
            "null,2026-10-01T00:00:00Z,0,0"
    })
    void completionTimeFiltersInclusiveFromAndExclusiveTo(String from, String to, long amount, long count) {
        Order first = saveOrder(100, 1, OrderStatus.COMPLETED, FROM);
        saveOrder(200, 1, OrderStatus.COMPLETED, MIDDLE);
        saveOrder(300, 1, OrderStatus.COMPLETED, TO);
        // 注文日が期間外でも完了日で集計する。
        entityManager.createNativeQuery("UPDATE orders SET ordered_at='2000-01-01' WHERE id=:id")
                .setParameter("id", first.getId()).executeUpdate();
        SalesResponse response = service.getSales(parseBound(from), parseBound(to));
        assertEquals(amount, response.getTotalSales());
        assertEquals(count, response.getTotalOrders());
    }

    @Test
    void legacyCompletedWithoutTimestampIsExcludedFromEveryRangeAndNeverBackfilled() {
        Order legacy = saveOrder(900, 1, OrderStatus.COMPLETED);
        entityManager.createNativeQuery("UPDATE orders SET completed_at=NULL WHERE id=:id")
                .setParameter("id", legacy.getId()).executeUpdate();
        entityManager.clear();
        assertNull(orders.findById(legacy.getId()).orElseThrow().getCompletedAt());
        for (Instant[] range : List.of(new Instant[]{null, null}, new Instant[]{FROM, null},
                new Instant[]{null, TO}, new Instant[]{FROM, TO})) {
            SalesResponse response = service.getSales(range[0], range[1]);
            assertEquals(0L, response.getTotalSales());
            assertEquals(0L, response.getTotalOrders());
        }
        entityManager.flush();
        assertNull(entityManager.createNativeQuery("SELECT completed_at FROM orders WHERE id=:id")
                .setParameter("id", legacy.getId()).getSingleResult());
    }

    @Test
    void instantRoundTripStoresUtcWithSixFractionalDigitsOnMariaDb() {
        Instant timestamp = Instant.parse("2026-10-15T21:00:00.123456+09:00");
        Order order = saveOrder(100, 1, OrderStatus.COMPLETED, timestamp);
        entityManager.clear();
        assertEquals(timestamp, orders.findById(order.getId()).orElseThrow().getCompletedAt());
        assertEquals("2026-10-15 12:00:00.123456", entityManager.createNativeQuery(
                "SELECT DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id=:id")
                .setParameter("id", order.getId()).getSingleResult());
    }

    private static Instant parseBound(String value) {
        return "null".equals(value) ? null : Instant.parse(value);
    }
}
