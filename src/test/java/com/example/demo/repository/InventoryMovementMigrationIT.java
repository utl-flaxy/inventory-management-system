package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mariadb.MariaDBContainer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class InventoryMovementMigrationIT {
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("inventory_migration");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static List<Map<String,Object>> legacyHistory;
    static List<Map<String,Object>> legacyProducts;
    static Map<String,String> migrationHashes;
    static int migrated;
    @BeforeAll static void migrateV4DatabaseWithoutFabricatingHistory() throws Exception {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES(1,'legacy','test-hash','ROLE_USER'),(2,'actor','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'legacy',100,10,4),(2,'other',100,20,3)");
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,version) VALUES(1,1,100,'PENDING','2000-01-01 00:00:00.123456','2000-01-01 00:00:00.123456',0)");
        legacyHistory = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        legacyProducts = jdbc.queryForList("SELECT * FROM products ORDER BY id");
        migrationHashes = hashes();
        migrated = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;
    }
    static Map<String,String> hashes() throws Exception {
        Map<String,String> hashes = new TreeMap<>();
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("V[1-4]__.*")).toList())
                hashes.put(file.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return hashes;
    }
    @BeforeEach void clearMovements() { jdbc.update("DELETE FROM inventory_movements"); }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class); }
    static Map<String,Object> valid() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("product_id", 1L); row.put("delta", 1); row.put("stock_before", 10); row.put("stock_after", 11);
        row.put("reason", "RECEIPT"); row.put("source_type", "MANUAL"); row.put("source_id", null);
        row.put("actor_user_id", 2L); row.put("note", null); row.put("created_at", java.sql.Timestamp.valueOf("2026-10-10 01:02:03.123456"));
        return row;
    }
    void insert(Map<String,Object> row) {
        jdbc.update("INSERT INTO inventory_movements(product_id,delta,stock_before,stock_after,reason,source_type,source_id,actor_user_id,note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)", row.values().toArray());
    }
    @Test void v5AndV6AreAppliedWithUnchangedLegacyRowsHashesAndFlywayHistory() throws Exception {
        assertEquals(5, migrated); assertEquals(0, count());
        assertEquals(legacyProducts, jdbc.queryForList("SELECT * FROM products ORDER BY id"));
        assertEquals(legacyHistory, jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2','3','4') ORDER BY installed_rank"));
        assertEquals(migrationHashes, hashes()); assertEquals(4, migrationHashes.size());
        assertEquals("V5__create_inventory_movements.sql", jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version='5'", String.class));
        assertNotNull(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='5'", Integer.class));
        var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.validate(); assertEquals("9", flyway.info().current().getVersion().getVersion());
    }
    @Test void applicationRestartsValidateHibernateAndDoNotReapplyMigrationsOrCreateSnapshots() {
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        for (int restart = 0; restart < 2; restart++) {
            try (var context = application()) {
                assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
                assertEquals(0, context.getBean(Flyway.class).info().pending().length);
            }
            assertEquals(0, count()); assertEquals(legacyProducts, jdbc.queryForList("SELECT * FROM products ORDER BY id"));
            assertEquals(history, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        }
    }
    ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.datasource.url=" + database.getJdbcUrl(), "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(), "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                "--JWT_SECRET=" + Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.show-sql=false", "--server.port=0", "--spring.main.banner-mode=off");
    }

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of("delta", 0, "stock_after", 10),
                Arguments.of("stock_before", -1, "stock_after", 0),
                Arguments.of("stock_after", -1, "delta", -11),
                Arguments.of("stock_after", 12, "delta", 1),
                Arguments.of("stock_before", Integer.MAX_VALUE, "delta", Integer.MAX_VALUE),
                Arguments.of("reason", "OTHER", "source_type", "MANUAL"),
                Arguments.of("reason", "receipt", "source_type", "MANUAL"),
                Arguments.of("source_type", "OTHER", "reason", "RECEIPT"),
                Arguments.of("source_type", "manual", "reason", "RECEIPT"),
                Arguments.of("reason", "ORDER_CREATED", "source_type", "ORDER"),
                Arguments.of("reason", "ORDER_CANCELLED", "source_type", "ORDER"),
                Arguments.of("source_id", 1L, "source_type", "MANUAL"),
                Arguments.of("product_id", 999L, "delta", 1),
                Arguments.of("actor_user_id", 999L, "delta", 1),
                Arguments.of("delta", null, "stock_after", 11),
                Arguments.of("stock_before", null, "stock_after", 11),
                Arguments.of("stock_after", null, "delta", 1),
                Arguments.of("reason", null, "delta", 1),
                Arguments.of("source_type", null, "delta", 1),
                Arguments.of("product_id", null, "delta", 1),
                Arguments.of("actor_user_id", null, "delta", 1),
                Arguments.of("created_at", null, "delta", 1));
    }
    @ParameterizedTest @MethodSource("invalidRows")
    void rawInvalidValuesAreRejectedByNotNullCheckOrForeignKey(String first, Object firstValue, String second, Object secondValue) {
        var row = valid(); row.put(first, firstValue); row.put(second, secondValue);
        assertThrows(DataIntegrityViolationException.class, () -> insert(row));
        assertEquals(0, count());
    }
    @ParameterizedTest @ValueSource(strings = {"created-positive", "cancel-negative", "order-note", "missing-order"})
    void orderSourceMustHaveCorrectDirectionExistingOrderAndNullNote(String invalid) {
        var row = valid(); row.put("source_type", "ORDER"); row.put("source_id", 1L); row.put("reason", "ORDER_CREATED");
        row.put("delta", -1); row.put("stock_after", 9);
        switch (invalid) {
            case "created-positive" -> { row.put("delta", 1); row.put("stock_after", 11); }
            case "cancel-negative" -> row.put("reason", "ORDER_CANCELLED");
            case "order-note" -> row.put("note", "forbidden");
            default -> row.put("source_id", 999L);
        }
        assertThrows(DataIntegrityViolationException.class, () -> insert(row)); assertEquals(0, count());
    }
    @ParameterizedTest @CsvSource({"0,2147483647,2147483647", "2147483647,-2147483647,0"})
    void widenedDbArithmeticAcceptsValidIntegerBoundaries(int before, int delta, int after) {
        var row = valid(); row.put("stock_before", before); row.put("delta", delta); row.put("stock_after", after);
        insert(row); assertEquals(1, count());
    }
    @ParameterizedTest @ValueSource(strings = {"products", "users", "orders"})
    void auditForeignKeysRestrictDeletesAndDoNotCascade(String table) {
        var row = valid(); row.put("source_type", "ORDER"); row.put("source_id", 1L); row.put("reason", "ORDER_CREATED");
        row.put("delta", -1); row.put("stock_after", 9); insert(row);
        int id = table.equals("users") ? 2 : 1;
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM " + table + " WHERE id=?", id));
        assertEquals(1, count()); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id));
    }

    @Test void columnsBinaryEnumsMicrosecondsChecksForeignKeysAndIndexMatchContract() {
        var columns = jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,COLLATION_NAME,DATETIME_PRECISION,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements' ORDER BY ORDINAL_POSITION");
        assertEquals(List.of("id","product_id","delta","stock_before","stock_after","reason","source_type","source_id","actor_user_id","note","created_at","idempotency_key","request_fingerprint"),
                columns.stream().map(c -> c.get("COLUMN_NAME")).toList());
        for (String name : List.of("delta", "stock_before", "stock_after")) {
            var column = columns.stream().filter(c -> c.get("COLUMN_NAME").equals(name)).findFirst().orElseThrow();
            assertEquals("int", column.get("DATA_TYPE"));
        }
        for (String name : List.of("reason", "source_type")) {
            var column = columns.stream().filter(c -> c.get("COLUMN_NAME").equals(name)).findFirst().orElseThrow();
            assertEquals("utf8mb4_bin", column.get("COLLATION_NAME"));
        }
        var time = columns.get(10); assertEquals("datetime", time.get("DATA_TYPE"));
        assertEquals(6, ((Number) time.get("DATETIME_PRECISION")).intValue());
        assertEquals(500, ((Number) columns.get(9).get("CHARACTER_MAXIMUM_LENGTH")).intValue());
        assertEquals(List.of("source_id","note","idempotency_key","request_fingerprint"), columns.stream().filter(c -> c.get("IS_NULLABLE").equals("YES")).map(c -> c.get("COLUMN_NAME")).toList());
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements' AND CONSTRAINT_TYPE='CHECK'", Integer.class));
        var keys = jdbc.queryForList("SELECT DELETE_RULE,UPDATE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements'");
        assertEquals(3, keys.size()); keys.forEach(k -> { assertEquals("RESTRICT", k.get("DELETE_RULE")); assertEquals("RESTRICT", k.get("UPDATE_RULE")); });
        assertEquals(List.of("product_id","created_at","id"), jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory_movements' AND INDEX_NAME='idx_inventory_movements_product_created_id' ORDER BY SEQ_IN_INDEX", String.class));
    }
    @Test void actualHistoryQueryUsesCompositeIndexWithoutFilesort() {
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 1000; i++) batch.add(new Object[]{i < 20 ? 1 : 2});
        jdbc.batchUpdate("INSERT INTO inventory_movements(product_id,delta,stock_before,stock_after,reason,source_type,actor_user_id,created_at) VALUES(?,1,10,11,'RECEIPT','MANUAL',2,'2026-10-10 01:02:03.123456')", batch);
        jdbc.execute("ANALYZE TABLE inventory_movements");
        var plan = jdbc.queryForMap("EXPLAIN SELECT * FROM inventory_movements WHERE product_id=1 ORDER BY created_at DESC,id DESC LIMIT 20");
        assertEquals("idx_inventory_movements_product_created_id", plan.get("key"), plan.toString());
        assertFalse(Objects.toString(plan.get("Extra"), "").contains("filesort"), plan.toString());
    }
}
