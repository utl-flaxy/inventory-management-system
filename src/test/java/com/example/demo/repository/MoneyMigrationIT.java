package com.example.demo.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MoneyMigrationIT {
    // 各ケースで新しいV1 DBを作り、既存データを持つV1 -> V2を実際に検証する。
    @Container
    final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("money_migration");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private static final Map<String, String> MONEY_COLUMNS = Map.of(
            "products", "price", "orders", "total_price", "order_items", "price");

    @BeforeEach
    void existingV1Database() {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(),
                database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','historical-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (1,'historical-apple',2000000000,9)");
        jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at) VALUES (1,1,2000000000,'PENDING','2000-01-01','2000-01-01')");
        jdbc.update("INSERT INTO order_items (id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (1,1,2000000000,'historical-apple',1,1)");
        MONEY_COLUMNS.forEach((table, column) -> assertEquals("int", columnType(table, column)));
    }

    private Flyway migrateToV2() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).target("2").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        return flyway;
    }

    @Test
    void existingAmountsAllOtherDataAndAppliedV1ChecksumArePreserved() {
        var before = dataSnapshot();
        var v1History = jdbc.queryForMap("SELECT * FROM flyway_schema_history WHERE version='1'");
        var flyway = migrateToV2();
        flyway.validate();
        assertEquals(before, dataSnapshot());
        assertEquals(v1History, jdbc.queryForMap("SELECT * FROM flyway_schema_history WHERE version='1'"));
        assertNotNull(v1History.get("checksum"));
        assertEquals(List.of("1", "2"), jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class));
        MONEY_COLUMNS.forEach((table, column) -> assertEquals(2_000_000_000L,
                jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id=1", Long.class)));
    }

    @Test
    void bigintNotNullChecksForeignKeysIndexesAndVersionDefaultsArePreserved() {
        var constraints = constraints();
        var indexes = indexes();
        var references = jdbc.queryForList("SELECT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,UPDATE_RULE,DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() ORDER BY TABLE_NAME,CONSTRAINT_NAME");
        migrateToV2();
        assertEquals(constraints, constraints());
        assertEquals(indexes, indexes());
        assertEquals(references, jdbc.queryForList("SELECT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,UPDATE_RULE,DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() ORDER BY TABLE_NAME,CONSTRAINT_NAME"));
        MONEY_COLUMNS.forEach((table, column) -> {
            assertEquals("bigint", columnType(table, column));
            assertEquals("NO", jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", String.class, table, column));
            assertNull(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", String.class, table, column));
        });
        assertEquals("int", columnType("products", "stock"));
        assertEquals("int", columnType("order_items", "quantity"));
        for (String table : List.of("products", "orders")) {
            assertEquals("bigint", columnType(table, "version"));
            assertEquals("0", jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='version'", String.class, table));
        }
    }

    @Test
    void rawInsertAndUpdateAcceptZeroAmountsAboveIntegerMaximumAndSignedBigintMaximum() {
        migrateToV2();
        long id = 2;
        for (long amount : new long[]{0L, 2_147_483_648L, 3_000_000_000L, Long.MAX_VALUE}) {
            jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (?,'large',?,10)", id, amount);
            jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at) VALUES (?,1,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, amount);
            jdbc.update("INSERT INTO order_items (id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (?,1,?,'large',?,?)", id, amount, id, id);
            for (var entry : MONEY_COLUMNS.entrySet()) {
                String table = entry.getKey(), column = entry.getValue();
                assertEquals(amount, jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id=?", Long.class, id));
                jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=1", amount);
                assertEquals(amount, jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id=1", Long.class));
            }
            id++;
        }
    }

    @Test
    void negativeNullAndOutOfBigintValuesAreRejectedWithoutChangingData() {
        migrateToV2();
        var before = dataSnapshot();
        for (var entry : MONEY_COLUMNS.entrySet()) {
            String table = entry.getKey(), column = entry.getValue();
            String constraint = switch (table) {
                case "products" -> "ck_products_price_nonnegative";
                case "orders" -> "ck_orders_total_nonnegative";
                default -> "ck_order_items_price_nonnegative";
            };
            var negative = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE " + table + " SET " + column + "=-1 WHERE id=1"));
            assertTrue(negative.getMostSpecificCause().getMessage().contains(constraint));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE " + table + " SET " + column + "=NULL WHERE id=1"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE " + table + " SET " + column + "=9223372036854775808 WHERE id=1"));
        }
        assertEquals(before, dataSnapshot());
    }

    @Test
    void appliedV2HasNoPendingMigrationAndIsNotReexecuted() {
        var flyway = migrateToV2();
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var data = dataSnapshot();
        assertEquals(0, flyway.info().pending().length);
        assertEquals(0, Flyway.configure().dataSource(dataSource).target("2").load().migrate().migrationsExecuted);
        assertEquals(history, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        assertEquals(data, dataSnapshot());
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", String.class, table, column);
    }

    private Map<String, List<Map<String, Object>>> dataSnapshot() {
        // CASTで旧INT/新BIGINTのJDBC wrapper差だけを除き、値とその他全列を比較する。
        return Map.of("users", jdbc.queryForList("SELECT * FROM users ORDER BY id"),
                "products", jdbc.queryForList("SELECT id,name,CAST(price AS SIGNED) AS price,stock,version FROM products ORDER BY id"),
                "orders", jdbc.queryForList("SELECT id,customer_id,CAST(total_price AS SIGNED) AS total_price,status,ordered_at,updated_at,version FROM orders ORDER BY id"),
                "order_items", jdbc.queryForList("SELECT id,quantity,CAST(price AS SIGNED) AS price,product_name_snapshot,order_id,product_id FROM order_items ORDER BY id"));
    }

    private List<Map<String, Object>> constraints() {
        return jdbc.queryForList("SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() ORDER BY TABLE_NAME,CONSTRAINT_NAME");
    }

    private List<Map<String, Object>> indexes() {
        return jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
    }
}
