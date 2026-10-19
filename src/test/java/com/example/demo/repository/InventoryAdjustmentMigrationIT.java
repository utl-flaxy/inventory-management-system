package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mariadb.MariaDBContainer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class InventoryAdjustmentMigrationIT {
    private static final String UNIQUE = "uk_inventory_movements_actor_idempotency_key";
    private static final String LEGACY_COLUMNS = "id,product_id,delta,stock_before,stock_after,reason,source_type,source_id,actor_user_id,note,created_at";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("inventory_idempotency_migration");
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static Map<String,String> hashes;
    static List<Map<String,Object>> history, legacyMovements;
    static int applied;

    @BeforeAll static void migrateFromActualV5Data() throws Exception {
        source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("5").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES "
                + "(1,'alice','private-hash','ROLE_USER'),(2,'admin','private-hash','ROLE_ADMIN'),(3,'other','private-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES (1,'apple',100,15,9),(2,'orange',100,20,4)");
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,version) "
                + "VALUES(1,1,100,'PENDING','2000-01-01 00:00:00.123456','2000-01-01 00:00:00.123456',0)");
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,quantity,price,product_name_snapshot) VALUES(1,1,2,1,100,'orange')");
        jdbc.update("INSERT INTO inventory_movements(id,product_id,delta,stock_before,stock_after,reason,source_type,source_id,actor_user_id,note,created_at) "
                + "VALUES(1,1,5,10,15,'RECEIPT','MANUAL',NULL,2,'legacy note','2000-01-01 00:00:00.123456'),"
                + "(2,2,-1,21,20,'ORDER_CREATED','ORDER',1,1,NULL,'2000-01-01 00:00:00.654321')");
        history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        legacyMovements = jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY id");
        hashes = migrationHashes();
        applied = Flyway.configure().dataSource(source).load().migrate().migrationsExecuted;
    }

    @BeforeEach void clearNewRows() { jdbc.update("DELETE FROM inventory_movements WHERE id>2"); }

    @Test void v6AndV7PreserveLegacyRowsAndV1ToV5History() throws Exception {
        assertEquals(2, applied); assertEquals(5, hashes.size()); assertEquals(hashes, migrationHashes());
        assertEquals(history, jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') ORDER BY installed_rank"));
        assertEquals(legacyMovements, jdbc.queryForList("SELECT " + LEGACY_COLUMNS + " FROM inventory_movements ORDER BY id"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE idempotency_key IS NULL AND request_fingerprint IS NULL", Integer.class));
        assertEquals("V6__add_inventory_adjustment_idempotency.sql", jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version='6'", String.class));
        assertEquals(List.of(15,20), jdbc.queryForList("SELECT stock FROM products ORDER BY id", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
    }

    @Test void nullableAsciiBinaryColumnsAndActorScopedUniqueMatchContract() {
        var columns = jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLLATION_NAME,CHARACTER_MAXIMUM_LENGTH "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements' "
                + "AND COLUMN_NAME IN ('idempotency_key','request_fingerprint') ORDER BY ORDINAL_POSITION");
        assertEquals(2, columns.size());
        assertEquals("varchar", columns.get(0).get("DATA_TYPE"));
        assertEquals("char", columns.get(1).get("DATA_TYPE"));
        assertEquals(128, ((Number) columns.get(0).get("CHARACTER_MAXIMUM_LENGTH")).intValue());
        assertEquals(64, ((Number) columns.get(1).get("CHARACTER_MAXIMUM_LENGTH")).intValue());
        columns.forEach(c -> { assertEquals("ascii_bin", c.get("COLLATION_NAME")); assertEquals("YES", c.get("IS_NULLABLE")); });
        assertEquals(List.of("actor_user_id","idempotency_key"), jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements' AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX", String.class, UNIQUE));
        assertEquals(0, jdbc.queryForObject("SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='inventory_movements' AND INDEX_NAME=? LIMIT 1", Integer.class, UNIQUE));
    }

    @Test void sameActorKeyDuplicateIsRealMariaDb1062ForExactConstraint() {
        insert(2, "key", "a".repeat(64), "MANUAL");
        var failure = assertThrows(DataIntegrityViolationException.class, () -> insert(2, "key", "b".repeat(64), "MANUAL"));
        var sql = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(1062, sql.getErrorCode());
        assertTrue(sql.getMessage().endsWith("for key '" + UNIQUE + "'"), sql.getMessage());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE idempotency_key='key'", Integer.class));
    }

    @Test void caseSensitiveKeysAndDifferentActorsAreIndependent() {
        insert(2, "KeyABC", "a".repeat(64), "MANUAL");
        insert(2, "keyabc", "a".repeat(64), "MANUAL");
        insert(3, "KeyABC", "a".repeat(64), "MANUAL");
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class));
    }

    @Test void multipleLegacyManualAndOrderRowsKeepNullMetadata() {
        insert(2, null, null, "MANUAL"); insert(2, null, null, "MANUAL");
        insert(1, null, null, "ORDER"); insert(1, null, null, "ORDER");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE idempotency_key IS NULL AND request_fingerprint IS NULL", Integer.class));
    }

    @ParameterizedTest @ValueSource(booleans = {true,false})
    void halfPresentMetadataIsRejected(boolean keyOnly) {
        assertThrows(DataIntegrityViolationException.class, () -> insert(2, keyOnly ? "key" : null, keyOnly ? null : "a".repeat(64), "MANUAL"));
    }

    @ParameterizedTest @ValueSource(strings = {"uppercase","short","long","nonhex"})
    void nonNullFingerprintMustBeExactly64LowercaseHex(String invalid) {
        String hash = switch (invalid) {
            case "uppercase" -> "A".repeat(64);
            case "short" -> "a".repeat(63);
            case "long" -> "a".repeat(65);
            default -> "g".repeat(64);
        };
        assertThrows(DataIntegrityViolationException.class, () -> insert(2, "key", hash, "MANUAL"));
    }

    @Test void orderSourceCannotHaveIdempotencyMetadata() {
        assertThrows(DataIntegrityViolationException.class, () -> insert(1, "order-key", "a".repeat(64), "ORDER"));
    }

    @Test void applicationRestartsValidateV7AndPreserveRowsWithPendingZero() {
        var before = jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY id");
        var beforeHistory = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        for (int restart=0; restart<2; restart++) {
            try (var context = new SpringApplicationBuilder(DemoApplication.class).run(
                    "--spring.datasource.url=" + database.getJdbcUrl(), "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword(), "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                    "--JWT_SECRET=" + Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                    "--server.port=0", "--spring.jpa.show-sql=false", "--spring.jpa.hibernate.ddl-auto=validate")) {
                assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
                var flyway = context.getBean(Flyway.class);
                flyway.validate(); assertEquals("7", flyway.info().current().getVersion().getVersion());
                assertEquals(0, flyway.info().pending().length); assertEquals(0, flyway.migrate().migrationsExecuted);
            }
            assertEquals(before, jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY id"));
            assertEquals(beforeHistory, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        }
    }

    private void insert(long actor, String key, String hash, String sourceType) {
        boolean order = sourceType.equals("ORDER");
        jdbc.update("INSERT INTO inventory_movements(product_id,delta,stock_before,stock_after,reason,source_type,source_id,actor_user_id,created_at,idempotency_key,request_fingerprint) "
                + "VALUES(1,?,10,?,?,?, ?,?,'2026-10-10 01:02:03.123456',?,?)",
                order ? -1 : 1, order ? 9 : 11, order ? "ORDER_CREATED" : "RECEIPT", sourceType, order ? 1L : null, actor, key, hash);
    }

    static Map<String,String> migrationHashes() throws Exception {
        Map<String,String> result = new TreeMap<>();
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("V[1-5]__.*")).toList())
                result.put(path.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
}
