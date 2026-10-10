package com.example.demo.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderConstraintMigrationTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private boolean productsCreated;
    private boolean orderItemsCreated;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                System.getProperty("issue10.migration.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB"),
                System.getProperty("issue10.migration.username", "sa"),
                System.getProperty("issue10.migration.password", ""), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, stock INT)");
        productsCreated = true;
        jdbc.execute("CREATE TABLE order_items (id BIGINT PRIMARY KEY, quantity INT)");
        orderItemsCreated = true;
    }

    @AfterEach
    void close() {
        try {
            if (orderItemsCreated) {
                jdbc.execute("DROP TABLE order_items");
            }
            if (productsCreated) {
                jdbc.execute("DROP TABLE products");
            }
        } finally {
            dataSource.destroy();
        }
    }

    private void migrate() {
        new ResourceDatabasePopulator(new FileSystemResource("docs/db/legacy/issue-10-order-invariants.sql"))
                .execute(dataSource);
    }

    @Test
    void 既存テーブルへSQL適用すると不正INSERTとUPDATEを拒否する() {
        jdbc.update("INSERT INTO products VALUES (1, 0)");
        jdbc.update("INSERT INTO order_items VALUES (1, 1)");

        migrate();

        for (Integer stock : new Integer[]{null, -1}) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO products VALUES (2, ?)", stock));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE products SET stock = ? WHERE id = 1", stock));
        }
        for (Integer quantity : new Integer[]{null, 0, -1}) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO order_items VALUES (2, ?)", quantity));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE order_items SET quantity = ? WHERE id = 1", quantity));
        }
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 1", Integer.class));
    }

    @Test
    void 既存の不正在庫は暗黙補正せずSQL適用を失敗させる() {
        jdbc.update("INSERT INTO products VALUES (1, NULL), (2, -1)");

        assertThrows(ScriptException.class, this::migrate);

        assertNull(jdbc.queryForObject("SELECT stock FROM products WHERE id = 1", Integer.class));
        assertEquals(-1, jdbc.queryForObject("SELECT stock FROM products WHERE id = 2", Integer.class));
    }

    @Test
    void 既存の不正明細数量は暗黙補正せずSQL適用を失敗させる() {
        jdbc.update("INSERT INTO products VALUES (1, 0)");
        jdbc.update("INSERT INTO order_items VALUES (1, NULL), (2, 0), (3, -1)");

        assertThrows(ScriptException.class, this::migrate);

        assertNull(jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 2", Integer.class));
        assertEquals(-1, jdbc.queryForObject("SELECT quantity FROM order_items WHERE id = 3", Integer.class));
    }
}
