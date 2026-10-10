package com.example.demo.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class LegacyDatabasePreflightIT {
    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("legacy_preflight");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void legacySchemaWithValidHistoricalData() {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // 意図的に制約のない旧DB fixture。V1の代替schemaとしては使用しない。
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(300), password VARCHAR(255), role VARCHAR(255))");
        jdbc.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT, stock INT, version BIGINT)");
        jdbc.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT, total_price INT, status VARCHAR(20), ordered_at DATETIME(6), updated_at DATETIME(6), version BIGINT)");
        jdbc.execute("CREATE TABLE order_items (id BIGINT PRIMARY KEY, quantity INT, price INT, product_name_snapshot VARCHAR(255), order_id BIGINT, product_id BIGINT)");
        jdbc.update("INSERT INTO users VALUES (1,'alice','test-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products VALUES (1,'current-apple',900,8,0)");
        jdbc.update("INSERT INTO orders VALUES (1,1,200,'PENDING','2000-01-01','2000-01-01',0)");
        jdbc.update("INSERT INTO order_items VALUES (1,2,100,'historical-apple',1,1)");
    }

    @AfterEach
    void dropDisposableFixture() {
        for (String table : List.of("order_items", "orders", "products", "users")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private List<List<Map<String, Object>>> snapshot() {
        return Stream.of("users", "products", "orders", "order_items")
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    private void preflight() {
        new ResourceDatabasePopulator(new FileSystemResource("docs/db/preflight.sql")).execute(dataSource);
    }

    static Stream<Arguments> corruptHistory() {
        return Stream.of(
                Arguments.of("negative stock", "UPDATE products SET stock=-1"),
                Arguments.of("null stock", "UPDATE products SET stock=NULL"),
                Arguments.of("zero quantity", "UPDATE order_items SET quantity=0"),
                Arguments.of("negative quantity", "UPDATE order_items SET quantity=-1"),
                Arguments.of("null quantity", "UPDATE order_items SET quantity=NULL"),
                Arguments.of("orphan item order", "UPDATE order_items SET order_id=999"),
                Arguments.of("orphan item product", "UPDATE order_items SET product_id=999"),
                Arguments.of("missing customer", "UPDATE orders SET customer_id=NULL"),
                Arguments.of("unknown customer", "UPDATE orders SET customer_id=999"),
                Arguments.of("total mismatch", "UPDATE orders SET total_price=201"),
                Arguments.of("empty order", "DELETE FROM order_items"),
                Arguments.of("missing snapshot", "UPDATE order_items SET product_name_snapshot=NULL"),
                Arguments.of("negative product price", "UPDATE products SET price=-1"),
                Arguments.of("negative snapshot price", "UPDATE order_items SET price=-1"),
                Arguments.of("negative total", "UPDATE orders SET total_price=-1"),
                Arguments.of("missing orderedAt", "UPDATE orders SET ordered_at=NULL"),
                Arguments.of("missing updatedAt", "UPDATE orders SET updated_at=NULL"),
                Arguments.of("missing product version", "UPDATE products SET version=NULL"),
                Arguments.of("missing order version", "UPDATE orders SET version=NULL"),
                Arguments.of("missing name", "UPDATE products SET name=NULL"),
                Arguments.of("missing password", "UPDATE users SET password=NULL"),
                Arguments.of("invalid role", "UPDATE users SET role='ROLE_SUPERUSER'"),
                Arguments.of("lowercase role", "UPDATE users SET role='role_user'"),
                Arguments.of("invalid status", "UPDATE orders SET status='PAID'"),
                Arguments.of("lowercase status", "UPDATE orders SET status='pending'"),
                Arguments.of("duplicate username", "INSERT INTO users VALUES (2,'alice','test-hash','ROLE_USER')"),
                Arguments.of("oversized username", "UPDATE users SET username=REPEAT('x',256)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptHistory")
    void preflightStopsOnCorruptHistoryWithoutGuessingDataOrWritingMigrationHistory(String problem, String corruption) {
        jdbc.update(corruption);
        var before = snapshot();
        var failure = assertThrows(ScriptStatementFailedException.class, this::preflight);
        assertTrue(failure.getMostSpecificCause().getMessage().contains("ck_issue15_no_violations"));
        assertEquals(before, snapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'", Integer.class));
    }

    @Test
    void validHistoricalSnapshotsPassWithoutBeingReplacedByCurrentProductData() {
        var before = snapshot();
        assertDoesNotThrow(this::preflight);
        assertEquals(before, snapshot());
    }
}
