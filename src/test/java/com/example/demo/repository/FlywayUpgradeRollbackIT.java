package com.example.demo.repository;

import io.jsonwebtoken.Jwts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** All database operations are confined to this test's disposable MariaDB container. */
@Testcontainers
class FlywayUpgradeRollbackIT {
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("flyway_rollback").withUsername("inventory_app")
            .withPassword(Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()))
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");

    @Test
    void baselineMigrationHistoryAndDataRemainValidAcrossUpgradeAndRollback() throws Exception {
        var dataSource = new MariaDbDataSource(database.getJdbcUrl());
        dataSource.setUser(database.getUsername());
        dataSource.setPassword(database.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var baselineLoader = new BaselineFlywayLoader(List.of("flyway-core", "flyway-mysql").stream()
                .map(name -> {
                    try { return Path.of("target/rollback-libraries/" + name + "-11.7.2.jar").toUri().toURL(); }
                    catch (java.net.MalformedURLException ex) { throw new IllegalStateException(ex); }
                }).toArray(URL[]::new), previous)) {
            Thread.currentThread().setContextClassLoader(baselineLoader);
            Class<?> baselineType = baselineLoader.loadClass("org.flywaydb.core.Flyway");
            assertNotSame(Flyway.class.getClassLoader(), baselineType.getClassLoader());
            Object config = baselineType.getMethod("configure").invoke(null);
            config.getClass().getMethod("dataSource", DataSource.class).invoke(config, dataSource);
            config.getClass().getMethod("locations", String[].class).invoke(config, (Object) new String[]{"classpath:db/migration"});
            Object baseline = config.getClass().getMethod("load").invoke(config);
            Object initial = baselineType.getMethod("migrate").invoke(baseline);
            assertEquals(6, initial.getClass().getField("migrationsExecuted").get(initial));
            jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1, 'rollback-fixture', 125, 10)");
            var history = jdbc.queryForList("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank");

            Thread.currentThread().setContextClassLoader(previous);
            var upgraded = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
            upgraded.validate();
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            assertEquals(history, jdbc.queryForList("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"));

            Thread.currentThread().setContextClassLoader(baselineLoader);
            baselineType.getMethod("validate").invoke(baseline);
            Object rollback = baselineType.getMethod("migrate").invoke(baseline);
            assertEquals(0, rollback.getClass().getField("migrationsExecuted").get(rollback));
            assertEquals(history, jdbc.queryForList("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
            assertEquals(125, jdbc.queryForObject("SELECT price FROM products WHERE id=1", Integer.class));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    static class BaselineFlywayLoader extends URLClassLoader {
        BaselineFlywayLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.flywaydb.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
        @Override public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/org.flywaydb.")) return findResources(name);
            return super.getResources(name);
        }
    }
}
