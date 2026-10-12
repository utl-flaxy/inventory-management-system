package com.example.demo.provisioning;

import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.demo.provisioning.InitialAdminProvisioner.Result.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class InitialAdminProvisioningIT {
    private static final String DATABASE_SECRET = UUID.randomUUID().toString();
    private static final String OPERATOR_SECRET = UUID.randomUUID().toString();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("initial_admin").withUsername("application_user").withPassword(DATABASE_SECRET)
            .withEnv("MARIADB_ROOT_HOST", "%");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("JWT_SECRET", () -> JWT_SECRET);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired ApplicationContext context;
    private final InitialAdminProvisioner provisioner = new InitialAdminProvisioner();

    @BeforeEach
    void freshUsersAndLeastPrivilegeAccount() throws Exception {
        assertEquals(0, countAdmins());
        try (Connection root = DriverManager.getConnection(database.getJdbcUrl(), "root", DATABASE_SECRET);
             var statement = root.createStatement()) {
            // Test-generated secret travels as a JDBC parameter, never process argv or output.
            try (var create = root.prepareStatement("CREATE USER IF NOT EXISTS 'bootstrap_operator'@'%' IDENTIFIED BY ?")) {
                create.setString(1, OPERATOR_SECRET);
                create.execute();
            }
            statement.execute("GRANT SELECT, INSERT ON initial_admin.users TO 'bootstrap_operator'@'%'");
        }
    }

    @AfterEach
    void cleanupOnlyThisDisposableDatabase() {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
    }

    Connection operator() throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), "bootstrap_operator", OPERATOR_SECRET);
    }

    int countAdmins() { return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ROLE_ADMIN'", Integer.class); }
    String hash(String username) { return jdbc.queryForObject("SELECT password FROM users WHERE username=?", String.class, username); }

    InitialAdminProvisioner.Result create(String username) throws Exception {
        return provisioner.provision(this::operator, username, () -> PASSWORD.toCharArray());
    }

    @Test
    void ordinaryStartupDoesNotCreateAnAdminOrRegisterAProvisioningBean() {
        assertEquals(0, countAdmins());
        assertTrue(context.getBeansOfType(InitialAdminProvisioner.class).isEmpty());
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
    }

    @Test
    void freshDatabaseGetsBcryptAdministratorWithOnlySelectAndInsertPrivileges() throws Exception {
        char[] owned = PASSWORD.toCharArray();
        assertEquals(CREATED, provisioner.provision(this::operator, "initial_operator", () -> owned));
        assertTrue(Arrays.equals(owned, new char[owned.length]));
        assertEquals(1, countAdmins());
        assertTrue(encoder.matches(PASSWORD, hash("initial_operator")));
        assertTrue(hash("initial_operator").startsWith("$2a$10$"));
        try (Connection connection = operator(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE users SET role='ROLE_USER'"));
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE forbidden_table (id INT)"));
        }
    }

    @Test
    void rerunWithSameOrDifferentNameNeverReadsANewPasswordOrChangesExistingAdmin() throws Exception {
        assertEquals(CREATED, create("initial_operator"));
        String original = hash("initial_operator");
        for (String name : List.of("initial_operator", "another_operator")) {
            assertEquals(ADMIN_PRESENT, provisioner.provision(this::operator, name, () -> {
                fail("Existing ADMIN must not request a replacement password"); return null;
            }));
        }
        assertEquals(1, countAdmins());
        assertTrue(original.equals(hash("initial_operator")), "Stored credential must remain unchanged");
    }

    @RepeatedTest(3)
    void independentConnectionsWithDifferentUsernamesCreateExactlyOneInitialAdministrator() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<InitialAdminProvisioner.Result>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String name = "initial_operator_" + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return create(name);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var actual = new ArrayList<InitialAdminProvisioner.Result>();
            for (var result : results) actual.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(1, actual.stream().filter(r -> r == CREATED).count());
            assertEquals(5, actual.stream().filter(r -> r == ADMIN_PRESENT).count());
            assertEquals(1, countAdmins());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void existingOrdinaryUserIsNeverPromotedOrOverwritten() throws Exception {
        jdbc.update("INSERT INTO users (username,password,role) VALUES (?,?, 'ROLE_USER')", "claimed_name", encoder.encode(PASSWORD));
        String original = hash("claimed_name");
        assertEquals(USERNAME_TAKEN, provisioner.provision(this::operator, "claimed_name", () -> {
            fail("A claimed username must not request a password"); return null;
        }));
        assertEquals(0, countAdmins());
        assertTrue(original.equals(hash("claimed_name")), "Existing credential must remain unchanged");
        assertEquals("ROLE_USER", jdbc.queryForObject("SELECT role FROM users WHERE username='claimed_name'", String.class));
    }

    @Test
    void abortedInputRollsBackAndConnectionCloseReleasesMutex() throws Exception {
        assertThrows(IOException.class, () -> provisioner.provision(this::operator, "initial_operator", () -> {
            throw new IOException("synthetic input abort");
        }));
        assertEquals(0, countAdmins());
        try (Connection connection = operator(); var query = connection.prepareStatement("SELECT IS_FREE_LOCK(?)")) {
            query.setString(1, InitialAdminProvisioner.lockName("initial_admin"));
            try (var result = query.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
        }
        assertEquals(CREATED, create("initial_operator"));
    }

    @Test
    void invalidPasswordRollsBackAndClearsOwnedBuffer() throws Exception {
        char[] password = "short".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(this::operator, "initial_operator", () -> password));
        assertTrue(Arrays.equals(password, new char[password.length]));
        assertEquals(0, countAdmins());
        assertEquals(CREATED, create("initial_operator"));
    }

    @Test
    void concurrentPublicRegistrationWinsUniqueConstraintWithoutBeingPromoted() throws Exception {
        assertThrows(SQLException.class, () -> provisioner.provision(this::operator, "claimed_during_input", () -> {
            jdbc.update("INSERT INTO users (username,password,role) VALUES (?,?, 'ROLE_USER')", "claimed_during_input", encoder.encode(PASSWORD));
            return PASSWORD.toCharArray();
        }));
        assertEquals(0, countAdmins());
        assertEquals("ROLE_USER", jdbc.queryForObject("SELECT role FROM users WHERE username='claimed_during_input'", String.class));
        assertEquals(CREATED, create("different_operator"));
    }

    @Test
    void heldMutexReturnsBusyWithoutRequestingPasswordOrCreatingAnAdmin() throws Exception {
        try (Connection holder = operator(); var query = holder.prepareStatement("SELECT GET_LOCK(?,0)")) {
            query.setString(1, InitialAdminProvisioner.lockName("initial_admin"));
            try (var result = query.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
            assertEquals(LOCK_BUSY, provisioner.provision(this::operator, "initial_operator", () -> {
                fail("Busy operation must not request an administrator password"); return null;
            }));
            assertEquals(0, countAdmins());
        }
        assertEquals(CREATED, create("initial_operator"));
    }

    @Test
    void missingRoleConstraintIsRefusedWithoutRepairingOrWritingTheSchema() throws Exception {
        jdbc.execute("ALTER TABLE users DROP CONSTRAINT ck_users_role_allowed");
        try {
            assertEquals(SCHEMA_NOT_READY, provisioner.provision(this::operator, "initial_operator", () -> {
                fail("Drifted schema must not request a password"); return null;
            }));
            assertEquals(0, countAdmins());
        } finally {
            jdbc.execute("ALTER TABLE users ADD CONSTRAINT ck_users_role_allowed CHECK (role IN ('ROLE_USER','ROLE_ADMIN'))");
        }
    }

    @Test
    void databaseCheckRejectsUnsupportedRole() throws Exception {
        try (Connection connection = operator(); var insert = connection.prepareStatement("INSERT INTO users (username,password,role) VALUES (?,?,?)")) {
            insert.setString(1, "invalid_role"); insert.setString(2, encoder.encode(PASSWORD)); insert.setString(3, "ROLE_SUPER_ADMIN");
            assertThrows(SQLException.class, insert::executeUpdate);
        }
        assertEquals(0, countAdmins());
    }

    @Test
    void stdinCommandConfirmsPasswordAndExitsWithoutPrintingSecrets(CapturedOutput logs) throws Exception {
        var output = new ByteArrayOutputStream();
        Map<String, String> environment = Map.of("PROVISION_JDBC_URL", database.getJdbcUrl(),
                "PROVISION_DB_USER", "bootstrap_operator", "PROVISION_ADMIN_USERNAME", "initial_operator");
        byte[] input = (OPERATOR_SECRET + "\n" + PASSWORD + "\n" + PASSWORD + "\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(0, InitialAdminCommand.execute(new String[]{"--passwords-stdin"}, environment,
                new ByteArrayInputStream(input), null, new PrintStream(output), DriverManager::getConnection));
        Arrays.fill(input, (byte) 0);
        assertEquals("initial-admin: CREATED", output.toString(StandardCharsets.UTF_8).strip());
        String token = login("initial_operator");
        String text = logs.getAll() + output.toString(StandardCharsets.UTF_8);
        for (String secret : List.of(DATABASE_SECRET, OPERATOR_SECRET, PASSWORD, JWT_SECRET, token, hash("initial_operator"))) {
            assertFalse(text.contains(secret), "Credential leaked into captured output");
        }
    }

    @Test
    void confirmationMismatchIsNonzeroAndLeavesNoAdministrator() throws Exception {
        Map<String, String> environment = Map.of("PROVISION_JDBC_URL", database.getJdbcUrl(),
                "PROVISION_DB_USER", "bootstrap_operator", "PROVISION_ADMIN_USERNAME", "initial_operator");
        var output = new ByteArrayOutputStream();
        byte[] input = (OPERATOR_SECRET + "\n" + PASSWORD + "\n" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(64, InitialAdminCommand.execute(new String[]{"--passwords-stdin"}, environment,
                new ByteArrayInputStream(input), null, new PrintStream(output), DriverManager::getConnection));
        Arrays.fill(input, (byte) 0);
        assertEquals("initial-admin: INVALID_INPUT", output.toString(StandardCharsets.UTF_8).strip());
        assertEquals(0, countAdmins());
    }

    @Test
    void publicAdminUsernameAndRoleInputStayUserWhileProvisionedAdminUsesNormalJwtAuthorization() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "admin", "password", PASSWORD, "role", "ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isForbidden());
        assertEquals(CREATED, create("initial_operator"));
        String token = login("initial_operator");
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/products").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "provisioned-admin-product", "price", 100, "stock", 3))))
                .andExpect(status().isOk());
    }

    String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
