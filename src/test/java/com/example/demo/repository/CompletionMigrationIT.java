package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CompletionMigrationIT {
    @Container final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("completion_migration");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void existingV2DatabaseWithEveryStatus() {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("2").load().migrate();
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','historical-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products (id,name,price,stock,version) VALUES (1,'historical-apple',3000000000,16,4)");
        int id = 0;
        for (String status : List.of("PENDING", "SHIPPED", "COMPLETED", "CANCELLED")) {
            id++;
            jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version) VALUES (?,1,3000000000,?,'2000-01-01 01:02:03.123456','2000-01-02 01:02:03.654321',2)", id, status);
            jdbc.update("INSERT INTO order_items (id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (?,1,3000000000,'historical-name',?,1)", id, id);
        }
    }

    @Test
    void v3PreservesExistingDataAndChecksumsLeavesLegacyNullAndPassesHibernateValidation() {
        var before = snapshot();
        var applied = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(before, snapshot());
        assertEquals(applied, jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE completed_at IS NULL", Integer.class));
        var column = jdbc.queryForMap("SELECT DATA_TYPE,DATETIME_PRECISION,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='completed_at'");
        assertEquals("datetime", column.get("DATA_TYPE"));
        assertEquals(6L, ((Number) column.get("DATETIME_PRECISION")).longValue());
        assertEquals("YES", column.get("IS_NULLABLE"));
        assertEquals(List.of("status", "completed_at"), jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_status_completed_at' ORDER BY SEQ_IN_INDEX", String.class));
        assertEquals("V3__add_order_completed_at.sql", jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version='3' AND success=1", String.class));
        assertNotNull(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='3'", Integer.class));

        try (var context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                "--JWT_SECRET=" + Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.show-sql=false", "--server.port=0")) {
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
            OrderRepository orders = context.getBean(OrderRepository.class);
            assertNull(orders.findById(3L).orElseThrow().getCompletedAt());
            assertEquals(0L, orders.aggregateSales(null, null).getTotalSales());
            assertEquals(0L, orders.aggregateSales(null, null).getTotalOrders());
        }
        assertEquals(before, snapshot());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE completed_at IS NULL", Integer.class));
    }

    @Test
    void v3IsNotReappliedAndOnlyConfirmedConditionalBackfillChangesRevenue() {
        Flyway.configure().dataSource(dataSource).load().migrate();
        var applied = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(0, Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted);
        assertEquals(applied, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        assertEquals(1, jdbc.update("UPDATE orders SET completed_at='2026-10-15 12:00:00.123456' WHERE id=3 AND status='COMPLETED' AND completed_at IS NULL"));
        assertEquals(0, jdbc.update("UPDATE orders SET completed_at='2026-11-01' WHERE id=3 AND status='COMPLETED' AND completed_at IS NULL"));
        assertEquals(0, jdbc.update("UPDATE orders SET completed_at='2026-11-01' WHERE id=1 AND status='COMPLETED' AND completed_at IS NULL"));
        assertEquals(3_000_000_000L, jdbc.queryForObject("SELECT SUM(total_price) FROM orders WHERE status='COMPLETED' AND completed_at IS NOT NULL", Long.class));
        assertEquals("2026-10-15 12:00:00.123456", jdbc.queryForObject("SELECT DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id=3", String.class));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("users", jdbc.queryForList("SELECT * FROM users ORDER BY id"),
                "products", jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                "orders", jdbc.queryForList("SELECT id,customer_id,total_price,status,ordered_at,updated_at,version FROM orders ORDER BY id"),
                "items", jdbc.queryForList("SELECT * FROM order_items ORDER BY id"));
    }
}
