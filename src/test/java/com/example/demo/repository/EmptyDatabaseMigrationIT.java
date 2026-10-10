package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class EmptyDatabaseMigrationIT {
    // 各テストで完全に新しいDBを使用。cleanや本番DBは使用しない。
    @Container
    final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("migration_startup");

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private ConfigurableApplicationContext start(String... overrides) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                "--JWT_SECRET=" + Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                "--server.port=0", "--spring.jpa.show-sql=false", "--spring.main.banner-mode=off"));
        arguments.addAll(List.of(overrides));
        return new SpringApplicationBuilder(DemoApplication.class).run(arguments.toArray(String[]::new));
    }

    private int domainTables() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('users','products','orders','order_items','inventory_movements')", Integer.class);
    }

    @Test
    void emptyDatabaseMigratesValidatesAndRestartsWithoutReapplyingV1V2V3OrV4() {
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class));
        try (var context = start()) {
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("true", context.getEnvironment().getProperty("spring.flyway.enabled"));
            assertEquals("false", context.getEnvironment().getProperty("spring.flyway.baseline-on-migrate"));
            assertEquals("true", context.getEnvironment().getProperty("spring.flyway.clean-disabled"));
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
            var flyway = context.getBean(Flyway.class);
            assertEquals(0, flyway.info().pending().length);
            assertEquals("5", flyway.info().current().getVersion().toString());
            assertEquals(5, domainTables());
        }
        var history = jdbc().queryForList("SELECT version, description, type, script, checksum, success, installed_on FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(5, history.size());
        assertEquals("1", history.get(0).get("version"));
        assertEquals("baseline schema", history.get(0).get("description"));
        assertEquals("SQL", history.get(0).get("type"));
        assertEquals("V1__baseline_schema.sql", history.get(0).get("script"));
        assertNotNull(history.get(0).get("checksum"));
        assertEquals(true, history.get(0).get("success"));
        assertEquals("2", history.get(1).get("version"));
        assertEquals("widen money columns to bigint", history.get(1).get("description"));
        assertEquals("SQL", history.get(1).get("type"));
        assertEquals("V2__widen_money_columns_to_bigint.sql", history.get(1).get("script"));
        assertNotNull(history.get(1).get("checksum"));
        assertEquals(true, history.get(1).get("success"));
        assertEquals("3", history.get(2).get("version"));
        assertEquals("V3__add_order_completed_at.sql", history.get(2).get("script"));
        assertNotNull(history.get(2).get("checksum"));
        assertEquals(true, history.get(2).get("success"));
        assertEquals("4", history.get(3).get("version"));
        assertEquals("V4__add_order_idempotency.sql", history.get(3).get("script"));
        assertNotNull(history.get(3).get("checksum"));
        assertEquals(true, history.get(3).get("success"));
        assertEquals("5", history.get(4).get("version"));
        assertEquals("V5__create_inventory_movements.sql", history.get(4).get("script"));
        assertNotNull(history.get(4).get("checksum"));
        jdbc().update("INSERT INTO users (username,password,role) VALUES ('alice','test-hash','ROLE_USER')");
        try (var context = start()) {
            assertEquals(0, context.getBean(Flyway.class).info().pending().length);
            assertEquals(0, context.getBean(Flyway.class).migrate().migrationsExecuted);
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
        }
        assertEquals(history, jdbc().queryForList("SELECT version, description, type, script, checksum, success, installed_on FROM flyway_schema_history ORDER BY installed_rank"));
        assertEquals("alice", jdbc().queryForObject("SELECT username FROM users", String.class));
    }

    @Test
    void missingMigrationsCannotBeReplacedByHibernateDdl() {
        var failure = assertThrows(RuntimeException.class,
                () -> { try (var ignored = start("--spring.flyway.locations=classpath:db/no-migrations")) { fail("schemaなしで起動してはいけない"); } });
        assertTrue(rootCause(failure).getMessage().contains("Schema-validation"));
        assertEquals(0, domainTables());
    }

    @Test
    void ordinaryStartupRefusesANonEmptyUnmanagedDatabaseWithoutBaseliningIt() {
        jdbc().execute("CREATE TABLE legacy_marker (id BIGINT PRIMARY KEY)");
        jdbc().update("INSERT INTO legacy_marker VALUES (1)");
        var failure = assertThrows(RuntimeException.class,
                () -> { try (var ignored = start()) { fail("未確認DBを自動baselineしてはいけない"); } });
        assertTrue(rootCause(failure).getMessage().contains("non-empty schema"));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'", Integer.class));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM legacy_marker", Integer.class));
        assertEquals(0, domainTables());
    }

    @Test
    void verifiedExistingSchemaCanBeManuallyBaselinedAndValidatedWithoutRecreatingTables() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__baseline_schema.sql")).execute(dataSource());
        jdbc().update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER')");
        jdbc().update("INSERT INTO products (id,name,price,stock) VALUES (1,'apple',100,8)");
        jdbc().update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at) VALUES (1,1,200,'PENDING','2000-01-01','2000-01-01')");
        jdbc().update("INSERT INTO order_items (id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (1,2,100,'historical-apple',1,1)");
        var before = jdbc().queryForList("SELECT id,quantity,CAST(price AS SIGNED) AS price,product_name_snapshot,order_id,product_id FROM order_items");
        new ResourceDatabasePopulator(new FileSystemResource("docs/db/preflight.sql")).execute(dataSource());
        Flyway.configure().dataSource(dataSource()).baselineVersion("1").baselineDescription("verified existing schema").load().baseline();
        try (var context = start()) {
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
            assertEquals(0, context.getBean(Flyway.class).info().pending().length);
        }
        assertEquals("BASELINE", jdbc().queryForObject("SELECT type FROM flyway_schema_history WHERE version='1'", String.class));
        assertEquals("SQL", jdbc().queryForObject("SELECT type FROM flyway_schema_history WHERE version='2' AND success=1", String.class));
        assertEquals(before, jdbc().queryForList("SELECT id,quantity,CAST(price AS SIGNED) AS price,product_name_snapshot,order_id,product_id FROM order_items"));
        assertEquals(8, jdbc().queryForObject("SELECT stock FROM products", Integer.class));
        assertEquals(200, jdbc().queryForObject("SELECT total_price FROM orders", Long.class));
    }

    @Test
    void schemaDriftAfterAnAppliedMigrationIsRejectedWithoutHibernateRepair() {
        Flyway.configure().dataSource(dataSource()).load().migrate();
        jdbc().execute("ALTER TABLE products DROP COLUMN version");
        var failure = assertThrows(RuntimeException.class,
                () -> { try (var ignored = start()) { fail("Hibernateがschemaを修復してはいけない"); } });
        assertTrue(rootCause(failure).getMessage().contains("missing column [version]"));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='version'", Integer.class));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='1'", Integer.class));
    }

    private Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
