package com.example.demo.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SchemaConstraintIT {
    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("schema_constraints");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final Map<String, List<String>> COLUMNS = Map.of(
            "users", List.of("username", "password", "role", "auth_uid", "token_version"),
            "products", List.of("name", "price", "stock", "version"),
            "orders", List.of("customer_id", "total_price", "status", "ordered_at", "updated_at", "version"),
            "order_items", List.of("quantity", "price", "product_name_snapshot", "order_id", "product_id"));

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO users (auth_uid,id, username, password, role) VALUES (UUID_v4(),1,'alice','test-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1,'apple',100,8)");
        jdbc.update("INSERT INTO orders (id, customer_id, total_price, status, ordered_at, updated_at) VALUES (1,1,200,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO order_items (id, quantity, price, product_name_snapshot, order_id, product_id) VALUES (1,2,100,'apple',1,1)");
    }

    static Stream<Arguments> invalidWrites() {
        return Stream.of(
                Arguments.of("users", "username", "NULL", "cannot be null"),
                Arguments.of("users", "password", "NULL", "cannot be null"),
                Arguments.of("users", "role", "NULL", "cannot be null"),
                Arguments.of("users", "role", "'ROLE_SUPERUSER'", "ck_users_role_allowed"),
                Arguments.of("users", "role", "'role_user'", "ck_users_role_allowed"),
                Arguments.of("products", "name", "NULL", "cannot be null"),
                Arguments.of("products", "price", "NULL", "cannot be null"),
                Arguments.of("products", "price", "-1", "ck_products_price_nonnegative"),
                Arguments.of("products", "stock", "NULL", "cannot be null"),
                Arguments.of("products", "stock", "-1", "ck_products_stock_nonnegative"),
                Arguments.of("products", "version", "NULL", "cannot be null"),
                Arguments.of("orders", "customer_id", "NULL", "cannot be null"),
                Arguments.of("orders", "customer_id", "999", "fk_orders_customer"),
                Arguments.of("orders", "total_price", "NULL", "cannot be null"),
                Arguments.of("orders", "total_price", "-1", "ck_orders_total_nonnegative"),
                Arguments.of("orders", "status", "NULL", "cannot be null"),
                Arguments.of("orders", "status", "'PAID'", "ck_orders_status_allowed"),
                Arguments.of("orders", "status", "'pending'", "ck_orders_status_allowed"),
                Arguments.of("orders", "ordered_at", "NULL", "cannot be null"),
                Arguments.of("orders", "updated_at", "NULL", "cannot be null"),
                Arguments.of("orders", "version", "NULL", "cannot be null"),
                Arguments.of("order_items", "quantity", "NULL", "cannot be null"),
                Arguments.of("order_items", "quantity", "0", "ck_order_items_quantity_positive"),
                Arguments.of("order_items", "quantity", "-1", "ck_order_items_quantity_positive"),
                Arguments.of("order_items", "price", "NULL", "cannot be null"),
                Arguments.of("order_items", "price", "-1", "ck_order_items_price_nonnegative"),
                Arguments.of("order_items", "product_name_snapshot", "NULL", "cannot be null"),
                Arguments.of("order_items", "order_id", "NULL", "cannot be null"),
                Arguments.of("order_items", "order_id", "999", "fk_order_items_order"),
                Arguments.of("order_items", "product_id", "NULL", "cannot be null"),
                Arguments.of("order_items", "product_id", "999", "fk_order_items_product"));
    }

    @ParameterizedTest(name = "{0}.{1} = {2}")
    @MethodSource("invalidWrites")
    void rawInsertAndUpdateAreRejectedByTheExpectedConstraint(String table, String column, String value, String constraint) {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=1");
        var update = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE " + table + " SET " + column + "=" + value + " WHERE id=1"));
        assertTrue(update.getMostSpecificCause().getMessage().contains(constraint), update.getMessage());
        List<String> columns = COLUMNS.get(table);
        String values = String.join(",", columns.stream().map(name -> name.equals(column) ? value
                : table.equals("users") && name.equals("username") ? "'another-user'"
                : table.equals("users") && name.equals("auth_uid") ? "UUID_v4()" : name).toList());
        var insert = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO " + table + " (" + String.join(",", columns) + ") SELECT " + values + " FROM " + table + " WHERE id=1"));
        assertTrue(insert.getMostSpecificCause().getMessage().contains(constraint), insert.getMessage());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=1"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    @Test
    void duplicateUsernameIsRejectedByTheNamedUniqueIndex() {
        var failure = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO users (auth_uid,username,password,role) VALUES (UUID_v4(),'alice','test-hash','ROLE_USER')"));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("uk_users_username"));
    }

    static Stream<Arguments> enumValues() {
        return Stream.concat(Stream.of("ROLE_USER", "ROLE_ADMIN").map(role -> Arguments.of("users", "role", role)),
                Stream.of("PENDING", "SHIPPED", "COMPLETED", "CANCELLED").map(status -> Arguments.of("orders", "status", status)));
    }

    @ParameterizedTest
    @MethodSource("enumValues")
    void everyCurrentRoleAndStatusIsAccepted(String table, String column, String value) {
        assertEquals(1, jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=1", value));
    }

    @Test
    void zeroMoneyAndStockAndDefaultVersionsRemainValid() {
        jdbc.update("UPDATE products SET price=0,stock=0 WHERE id=1");
        jdbc.update("UPDATE orders SET total_price=0 WHERE id=1");
        jdbc.update("UPDATE order_items SET price=0,quantity=1 WHERE id=1");
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM orders WHERE id=1", Long.class));
    }

    @Test
    void allRequiredColumnsAreNotNullAndVersionDefaultsAreExplicit() {
        COLUMNS.forEach((table, columns) -> columns.forEach(column -> assertEquals("NO",
                jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", String.class, table, column))));
        for (String table : List.of("products", "orders")) {
            assertEquals("0", jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='version'", String.class, table));
        }
        // V1の3本に、V5のproduct / actor / source orderの3本を追加。
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()", Integer.class));
    }

    @Test
    void explicitIndexesSupportOwnershipAndForeignKeyQueriesWithoutExtraCompositeIndex() {
        for (String[] index : List.of(new String[]{"users", "uk_users_username", "username"},
                new String[]{"orders", "idx_orders_customer", "customer_id"},
                new String[]{"order_items", "idx_order_items_order", "order_id"},
                new String[]{"order_items", "idx_order_items_product", "product_id"})) {
            assertEquals(List.of(index[2]), jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX", String.class, index[0], index[1]));
        }
        assertEquals(0, jdbc.queryForObject("SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND INDEX_NAME='uk_users_username'", Integer.class));
        assertTrue(jdbc.queryForMap("EXPLAIN SELECT * FROM orders WHERE customer_id=1").get("possible_keys").toString().contains("idx_orders_customer"));
        assertEquals("PRIMARY", jdbc.queryForMap("EXPLAIN SELECT * FROM orders WHERE id=1 AND customer_id=1").get("key"));
    }
}
