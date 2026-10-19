package com.example.demo.repository;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class BackupRestoreIT {
    private static final String DB = "backup_drill";
    private static final String SOURCE_PASSWORD = randomSecret();
    private static final String TARGET_PASSWORD = randomSecret();
    private static final String JWT = randomSecret();
    private static final String HASH = new BCryptPasswordEncoder().encode("test-only-backup-password57");
    private static final String USERNAME = "private-backup-user57";
    private static final String ORDER_KEY = "private-backup-order-key57";
    private static final String INVENTORY_KEY = "private-backup-inventory-key57";
    private static final String FINGERPRINT = "e".repeat(64);
    private static final List<String> TABLES = List.of("users", "products", "orders", "order_items",
            "inventory_movements", "auth_revocation_audit", "flyway_schema_history");
    @Container static final MariaDBContainer source = container(SOURCE_PASSWORD);
    @Container static final MariaDBContainer target = container(TARGET_PASSWORD);
    @TempDir static Path temp;
    private Path bundle;
    private JdbcTemplate sourceJdbc;
    private JdbcTemplate targetJdbc;

    private static MariaDBContainer container(String password) {
        return new MariaDBContainer("mariadb:12.2").withDatabaseName(DB)
                .withUsername("inventory_app").withPassword(password)
                // TestcontainersのMYSQL_*互換名に依存せず、実scriptにはComposeと同じMARIADB_*を渡す。
                .withEnv("MARIADB_DATABASE", DB).withEnv("MARIADB_USER", "inventory_app")
                .withEnv("MARIADB_PASSWORD", password).withEnv("MARIADB_ROOT_PASSWORD", password)
                .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                        "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    }

    private static DriverManagerDataSource dataSource(MariaDBContainer container) {
        return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @BeforeAll
    void prepareMigratedSourceAndActualBackup() throws Exception {
        sourceJdbc = new JdbcTemplate(dataSource(source));
        targetJdbc = new JdbcTemplate(dataSource(target));
        Flyway.configure().dataSource(dataSource(source)).locations("classpath:db/migration").load().migrate();
        sourceJdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES (UUID_v4(),10,?,?, 'ROLE_USER'),(UUID_v4(),11,?,?, 'ROLE_ADMIN')",
                USERNAME, HASH, "backup-admin57", HASH);
        sourceJdbc.update("UPDATE users SET token_version=3 WHERE id=10");
        sourceJdbc.update("INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES(10,10,'LOGOUT_ALL','SUCCEEDED','2026-10-19 00:00:00.123456',?)", java.util.UUID.randomUUID().toString());
        sourceJdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES (1,?,5000000000,14,3),(2,?,7,9,0)",
                "更新後の商品 🧪", "予備商品");
        String[] statuses = {"PENDING", "SHIPPED", "COMPLETED", "CANCELLED"};
        int[] quantities = {2, 2, 3, 5};
        int stock = 20;
        for (int i = 0; i < statuses.length; i++) {
            sourceJdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,version,completed_at,idempotency_key,request_fingerprint) VALUES (?,10,?,?, '2026-10-10 01:02:03.123456','2026-10-10 02:03:04.123456',1,?,?,?)",
                    i + 1, 5_000_000_000L * quantities[i], statuses[i],
                    statuses[i].equals("COMPLETED") ? "2026-10-10 02:03:04.123456" : null,
                    ORDER_KEY + i, FINGERPRINT);
            sourceJdbc.update("INSERT INTO order_items(id,quantity,price,product_name_snapshot,order_id,product_id) VALUES (?,?,5000000000,?,?,1)",
                    i + 1, quantities[i], "注文時の商品 🧪", i + 1);
            movement(i + 1, -quantities[i], stock, stock - quantities[i], "ORDER_CREATED", "ORDER", i + 1, 10, null, null, null);
            stock -= quantities[i];
        }
        movement(5, 5, 8, 13, "ORDER_CANCELLED", "ORDER", 4, 10, null, null, null);
        movement(6, 2, 13, 15, "RECEIPT", "MANUAL", null, 11, "入荷確認", INVENTORY_KEY, FINGERPRINT);
        movement(7, -1, 15, 14, "DAMAGE", "MANUAL", null, 11, "破損確認", INVENTORY_KEY + "-damage", FINGERPRINT);
        var before = rows(sourceJdbc);
        var root = temp.resolve("backups");
        var result = script("scripts/db/backup.sh", Map.of("DB_CONTAINER", source.getContainerId(), "BACKUP_ROOT", path(root)));
        assertEquals(0, result.exit(), "Actual backup script failed: " + result.errors());
        assertTrue(result.output().startsWith("Backup completed: "));
        try (var paths = Files.list(root)) {
            var published = paths.toList();
            assertEquals(1, published.size());
            bundle = published.get(0);
        }
        assertTrue(before.equals(rows(sourceJdbc)), "Backup changed source rows");
        assertNoSecrets(result.output());
    }

    private void movement(int id, int delta, int before, int after, String reason, String type, Integer order,
                          int actor, String note, String key, String fingerprint) {
        sourceJdbc.update("INSERT INTO inventory_movements(id,product_id,delta,stock_before,stock_after,reason,source_type,source_id,actor_user_id,note,created_at,idempotency_key,request_fingerprint) VALUES (?,1,?,?,?,?,?,?,?,?, '2026-10-10 03:04:05.123456',?,?)",
                id, delta, before, after, reason, type, order, actor, note, key, fingerprint);
    }

    @BeforeEach
    void resetOnlyDisposableTarget() throws Exception {
        // test専用containerのDBだけをreset。本番名やvolumeは使用しない。
        var reset = target.execInContainer("sh", "-c", "export MYSQL_PWD=\"$MARIADB_ROOT_PASSWORD\"; exec mariadb --protocol=socket --user=root --execute='DROP DATABASE `backup_drill`; CREATE DATABASE `backup_drill` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'");
        assertEquals(0, reset.getExitCode(), "Disposable target reset failed (output withheld)");
    }

    @Test
    void freshRestorePreservesAllRowsMetadataAndMigrationChecksums(CapturedOutput output) throws Exception {
        assertSuccess(restore(bundle));
        assertRestored();
        assertNoSecrets(output.getAll());
    }

    @Test
    void restoredDedicatedUserPassesFlywayAndHibernateValidation(CapturedOutput output) throws Exception {
        assertSuccess(restore(bundle));
        var flyway = Flyway.configure().dataSource(dataSource(target)).locations("classpath:db/migration").load();
        flyway.validate();
        assertEquals(0, flyway.info().pending().length);
        assertEquals(9, flyway.info().applied().length);
        var before = rows(targetJdbc);
        try (var context = new SpringApplicationBuilder(DemoApplication.class).profiles("prod").run(
                "--spring.datasource.url=" + target.getJdbcUrl(), "--spring.datasource.username=inventory_app",
                "--spring.datasource.password=" + TARGET_PASSWORD,
                "--spring.datasource.driver-class-name=" + target.getDriverClassName(), "--JWT_SECRET=" + JWT,
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.show-sql=false",
                "--spring.main.banner-mode=off", "--server.port=0", "--app.login.rate-limit.trusted-proxies=127.0.0.1")) {
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
            assertEquals("inventory_app", context.getEnvironment().getProperty("spring.datasource.username"));
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            context.getBean(Flyway.class).validate();
            assertEquals(0, context.getBean(Flyway.class).info().pending().length);
        }
        assertTrue(before.equals(rows(targetJdbc)), "Validation changed restored data/history");
        assertNoSecrets(output.getAll());
    }

    @Test
    void checksumTamperFailsBeforeAnyTargetChanges(CapturedOutput output) throws Exception {
        dummy();
        var corrupted = temp.resolve("corrupted");
        Files.createDirectory(corrupted);
        for (String name : List.of("dump.sql.gz", "dump.sha256", "manifest.properties")) {
            Files.copy(bundle.resolve(name), corrupted.resolve(name));
        }
        byte[] bytes = Files.readAllBytes(corrupted.resolve("dump.sql.gz"));
        bytes[bytes.length / 2] ^= 1;
        Files.write(corrupted.resolve("dump.sql.gz"), bytes);
        var result = restore(corrupted, "--replace", "--confirm-db", DB);
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("Backup checksum mismatch"));
        assertDummyUnchanged();
        assertNoSecrets(output.getAll());
    }

    @Test
    void nonEmptyTargetIsRejectedWithoutReplace(CapturedOutput output) throws Exception {
        dummy();
        var result = restore(bundle);
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("Non-empty target"));
        assertDummyUnchanged();
        assertNoSecrets(output.getAll());
    }

    @Test
    void wrongExactConfirmationCannotReplaceTarget(CapturedOutput output) throws Exception {
        dummy();
        var result = restore(bundle, "--replace", "--confirm-db", "backup_drilL");
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("exact --confirm-db"));
        assertDummyUnchanged();
        assertNoSecrets(output.getAll());
    }

    @Test
    void explicitReplaceRemovesOnlyDisposableTargetAndRestores(CapturedOutput output) throws Exception {
        dummy();
        var sourceBefore = rows(sourceJdbc);
        assertSuccess(restore(bundle, "--replace", "--confirm-db", DB));
        assertRestored();
        assertTrue(sourceBefore.equals(rows(sourceJdbc)), "Restore changed source database");
        assertNoSecrets(output.getAll());
    }

    @Test
    void bundleIsPrivateAndMetadataHasNoSecrets(CapturedOutput output) throws Exception {
        if (Files.getFileStore(bundle).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(bundle.getParent()));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(bundle));
            for (String name : List.of("dump.sql.gz", "dump.sha256", "manifest.properties")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bundle.resolve(name)));
            }
        }
        var manifest = Files.readString(bundle.resolve("manifest.properties"));
        assertEquals(5, manifest.lines().count());
        assertTrue(manifest.contains("format_version=1\nsource_database=" + DB + "\n"));
        assertTrue(manifest.contains("mariadb_server_version=12.2."));
        assertTrue(manifest.contains("dump_tool_version=12.2."));
        assertNoSecrets(manifest);
        assertNoSecrets(output.getAll());
    }

    private void assertRestored() {
        var sourceTables = sourceJdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
        var targetTables = targetJdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
        assertEquals(sourceTables, targetTables);
        var sourceRows = rows(sourceJdbc);
        var targetRows = rows(targetJdbc);
        for (String table : TABLES) {
            assertEquals(sourceRows.get(table).size(), targetRows.get(table).size(), "Row count differs: " + table);
            // password/hash/idempotency/fingerprintのexpected/actualをJUnit logへ出さない。
            assertTrue(sourceRows.get(table).equals(targetRows.get(table)), "Restored values differ: " + table);
        }
        assertEquals(14, targetJdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
        assertEquals(5_000_000_000L, targetJdbc.queryForObject("SELECT price FROM products WHERE id=1", Long.class));
        assertEquals(15_000_000_000L, targetJdbc.queryForObject("SELECT total_price FROM orders WHERE id=3", Long.class));
        assertEquals(4, targetJdbc.queryForObject("SELECT COUNT(DISTINCT status) FROM orders", Integer.class));
        assertEquals(7, targetJdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class));
        var history = targetJdbc.queryForList("SELECT version,description,type,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(3L, targetJdbc.queryForObject("SELECT token_version FROM users WHERE id=10",Long.class));
        assertEquals(1, targetJdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_audit",Integer.class));
        assertEquals(9, history.size());
        for (int i = 0; i < 9; i++) {
            assertEquals(Integer.toString(i + 1), history.get(i).get("version"));
            assertEquals(true, history.get(i).get("success"));
            assertNotNull(history.get(i).get("checksum"));
        }
    }

    private Map<String, List<Map<String, Object>>> rows(JdbcTemplate jdbc) {
        var rows = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + (table.equals("flyway_schema_history") ? "installed_rank" : "id")));
        }
        return rows;
    }

    private void dummy() { targetJdbc.execute("CREATE TABLE dummy(id INT PRIMARY KEY) ENGINE=InnoDB"); targetJdbc.update("INSERT INTO dummy VALUES (57)"); }
    private void assertDummyUnchanged() {
        assertEquals(57, targetJdbc.queryForObject("SELECT id FROM dummy", Integer.class));
        assertEquals(1, targetJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class));
    }

    private Result restore(Path input, String... extra) throws Exception {
        var args = new java.util.ArrayList<>(List.of("--bundle", path(input), "--container", target.getContainerId(), "--database", DB));
        args.addAll(List.of(extra));
        return script("scripts/db/restore.sh", Map.of(), args.toArray(String[]::new));
    }

    private void assertSuccess(Result result) { assertEquals(0, result.exit(), "Actual restore script failed: " + result.errors()); assertTrue(result.output().startsWith("Restore completed: ")); }
    private record Result(int exit, String output) {
        // client stderr/bodyを表示せず、scriptの固定summaryだけで失敗段階を診断する。
        String errors() { return output.lines().filter(line -> line.startsWith("ERROR: ")).reduce("", (a, b) -> a + b + "; "); }
    }
    private Result script(String script, Map<String, String> environment, String... arguments) throws Exception {
        var log = Files.createTempFile(temp, "script-", ".log");
        try {
            var command = new java.util.ArrayList<>(List.of(bash(), script));
            command.addAll(List.of(arguments));
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(environment);
            builder.environment().put("MSYS_NO_PATHCONV", "1");
            var process = builder.start();
            boolean completed = process.waitFor(60, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "Backup/restore subprocess timed out (output withheld)");
            var result = new Result(process.exitValue(), Files.readString(log));
            assertNoSecrets(result.output());
            return result;
        } finally { Files.deleteIfExists(log); }
    }

    private String bash() { return System.getProperty("os.name").startsWith("Windows") ? "C:/Program Files/Git/bin/bash.exe" : "/bin/bash"; }
    private static String path(Path path) { return path.toAbsolutePath().toString().replace('\\', '/'); }
    private static String randomSecret() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
    private void assertNoSecrets(String output) {
        for (String secret : List.of(SOURCE_PASSWORD, TARGET_PASSWORD, JWT, HASH, USERNAME, ORDER_KEY, INVENTORY_KEY, FINGERPRINT)) {
            assertFalse(output.contains(secret), "Sensitive output detected (value withheld)");
        }
    }
}
