package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
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
class IdempotencyMigrationIT {
    @Container final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("idempotency_migration");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void v3LegacyDatabase() {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("3").load().migrate();
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','historical-hash','ROLE_USER'),(2,'bob','historical-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products (id,name,price,stock,version) VALUES (1,'historical-apple',3000000000,16,4)");
        int id = 0;
        for (String status : List.of("PENDING", "SHIPPED", "COMPLETED", "CANCELLED")) {
            id++;
            jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version) VALUES (?,1,3000000000,?,'2000-01-01 01:02:03.123456','2000-01-02 01:02:03.654321',2)", id, status);
            jdbc.update("INSERT INTO order_items (id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (?,1,3000000000,'historical-name',?,1)", id, id);
        }
    }

    @Test
    void v4PreservesLegacyRowsAndV1V2V3HistoryAndBothApplicationRestartsHaveNoPendingMigration() {
        var before = legacySnapshot();
        var historical = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        assertEquals(6, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals("9", flyway.info().current().getVersion().getVersion());
        assertEquals(before, legacySnapshot());
        assertEquals(historical, jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2','3') ORDER BY installed_rank"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key IS NULL AND request_fingerprint IS NULL", Integer.class));
        assertEquals("V4__add_order_idempotency.sql", jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version='4' AND success=1", String.class));
        assertNotNull(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='4'", Integer.class));
        var applied = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        for (int restart = 0; restart < 2; restart++) {
            try (var context = application()) {
                assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
                assertEquals(0, context.getBean(Flyway.class).info().pending().length);
                var legacy = context.getBean(OrderRepository.class).findById(3L).orElseThrow();
                assertNull(legacy.getIdempotencyKey());
                assertNull(legacy.getRequestFingerprint());
                assertNull(legacy.getCompletedAt());
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals(applied, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(before, legacySnapshot());
        }
    }

    @Test
    void v4HasNullableAsciiBinaryColumnsWithCorrectTypesAndCustomerKeyUniqueIndex() {
        Flyway.configure().dataSource(dataSource).load().migrate();
        for (String column : List.of("idempotency_key", "request_fingerprint")) {
            var definition = jdbc.queryForMap("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,IS_NULLABLE,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME=?", column);
            boolean key = column.equals("idempotency_key");
            assertEquals(key ? "varchar" : "char", definition.get("DATA_TYPE"));
            assertEquals(key ? 128L : 64L, ((Number) definition.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
            assertEquals("YES", definition.get("IS_NULLABLE"));
            assertEquals("ascii", definition.get("CHARACTER_SET_NAME"));
            assertEquals("ascii_bin", definition.get("COLLATION_NAME"));
        }
        var index = jdbc.queryForList("SELECT COLUMN_NAME,NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND INDEX_NAME='uk_orders_customer_idempotency_key' ORDER BY SEQ_IN_INDEX");
        assertEquals(List.of("customer_id", "idempotency_key"), index.stream().map(row -> row.get("COLUMN_NAME")).toList());
        index.forEach(row -> assertEquals(0, ((Number) row.get("NON_UNIQUE")).intValue()));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key IS NULL", Integer.class));
    }

    @Test
    void uniqueRejectsSameCustomerAndKeyButAllowsOtherCustomersCaseDistinctKeysAndLegacyNulls() {
        Flyway.configure().dataSource(dataSource).load().migrate();
        insertKeyed(10, 1, "KeyA");
        assertThrows(DataIntegrityViolationException.class, () -> insertKeyed(11, 1, "KeyA"));
        insertKeyed(12, 2, "KeyA");
        insertKeyed(13, 1, "keya");
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key='KeyA'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key='keya'", Integer.class));
        assertEquals("a".repeat(64), jdbc.queryForObject("SELECT request_fingerprint FROM orders WHERE id=10", String.class));
    }

    private void insertKeyed(long id, long customer, String key) {
        jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version,idempotency_key,request_fingerprint) VALUES (?,?,0,'PENDING','2000-01-01','2000-01-01',0,?,?)", id, customer, key, "a".repeat(64));
    }

    private ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                "--JWT_SECRET=" + Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.show-sql=false", "--server.port=0");
    }

    private Map<String, List<Map<String, Object>>> legacySnapshot() {
        return Map.of("users", jdbc.queryForList("SELECT id,username,password,role FROM users ORDER BY id"),
                "products", jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                "orders", jdbc.queryForList("SELECT id,customer_id,total_price,status,ordered_at,updated_at,completed_at,version FROM orders ORDER BY id"),
                "items", jdbc.queryForList("SELECT * FROM order_items ORDER BY id"));
    }
}
