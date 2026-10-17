package com.example.demo.provisioning;

import com.example.demo.DemoApplication;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.DriverManager;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.example.demo.provisioning.InitialAdminProvisioner.Result.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class InitialAdminLifecycleIT {
    @Container final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("initial_admin_lifecycle").withUsername("application_user")
            .withPassword(UUID.randomUUID().toString());

    JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    ConfigurableApplicationContext start() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("disposable-admin-lifecycle", Map.of(
                "spring.datasource.url", database.getJdbcUrl(), "spring.datasource.username", database.getUsername(),
                "spring.datasource.password", database.getPassword(), "spring.jpa.show-sql", "false",
                "JWT_SECRET", Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()),
                "server.port", "0", "spring.main.banner-mode", "off",
                "app.login.rate-limit.trusted-proxies", "127.0.0.1")));
        return new SpringApplicationBuilder(DemoApplication.class).environment(environment).profiles("prod").run();
    }

    @Test
    void restartPreservesInitialAdministratorCredentialRoleIdAndMigrationHistory() throws Exception {
        try (var app = start()) {
            assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        }
        String password = UUID.randomUUID().toString();
        var provisioner = new InitialAdminProvisioner();
        assertEquals(CREATED, provisioner.provision(
                () -> DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword()),
                "initial_operator", password::toCharArray));
        var user = jdbc().queryForMap("SELECT id,username,password,role FROM users");
        var history = jdbc().queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank");
        try (var app = start()) {
            assertTrue(user.equals(jdbc().queryForMap("SELECT id,username,password,role FROM users")), "Stored administrator changed after restart");
            assertEquals(history, jdbc().queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(ADMIN_PRESENT, provisioner.provision(
                    () -> DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword()),
                    "new_operator", () -> { fail("Restart must not permit replacement"); return null; }));
        }
    }

    @Test
    void unmigratedDatabaseIsRefusedWithoutCreatingTablesOrRequestingPassword() throws Exception {
        assertEquals(SCHEMA_NOT_READY, new InitialAdminProvisioner().provision(
                () -> DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword()),
                "initial_operator", () -> { fail("No secret is needed for missing schema"); return null; }));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class));
    }
}
