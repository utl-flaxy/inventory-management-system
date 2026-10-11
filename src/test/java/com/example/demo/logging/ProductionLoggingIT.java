package com.example.demo.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.example.demo.exception.ApiProblemFactory;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.open-in-view=false")
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ProductionLoggingIT {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String PASSWORD = "super-secret-password";
    private static final String KEY = "idempotency-secret-key";
    private static final String INVENTORY_PATH = "/api/v1/admin/products/1/inventory-adjustments";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("production_logging").withUsername("inventory_app");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean InventoryMovementRepository movements;
    @MockitoSpyBean ProductRepository products;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String userToken;
    private String adminToken;
    private long adminId;

    @BeforeEach
    void fixtures() throws Exception {
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        assertEquals(200, post("/auth/register", credentials("alice"), null, null).statusCode());
        assertEquals(200, post("/auth/register", credentials("race-user"), null, null).statusCode());
        jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username='race-user'");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='race-user'", Long.class);
        userToken = token("alice");
        adminToken = token("race-user");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'product',100,10,0)");
        for (String name : List.of("org.mariadb.jdbc.message.server.ErrorPacket",
                "org.hibernate.engine.jdbc.spi.SqlExceptionHelper")) {
            assertEquals(Level.OFF, ((Logger) LoggerFactory.getLogger(name)).getEffectiveLevel());
        }
    }

    @Test
    void realUsernameDuplicateKeeps409WithoutDriverOrHibernateConsoleLeak(CapturedOutput output) throws Exception {
        // precheckの後に競合した状況を再現し、実DBのUNIQUE違反を発生させる。
        doReturn(Optional.empty()).when(users).findByUsername("alice");
        var response = post("/auth/register", credentials("alice"), null, null);
        assertProblem(response, 409, "USERNAME_ALREADY_EXISTS", "ユーザー名は既に使用されています", "/auth/register");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='alice'", Integer.class));
        assertProductionLogs(output, Set.of("auth.register.succeeded", "auth.login.succeeded", "http.request.completed"));
    }

    @Test
    void orderAndRealInventoryDuplicateKeep409RollbackAndCommandEvents(CapturedOutput output) throws Exception {
        var created = post("/api/v1/me/orders", orderBody(2), userToken, KEY);
        assertEquals(200, created.statusCode());
        long id = json.readTree(created.body()).path("id").asLong();
        assertProblem(post("/api/v1/me/orders", orderBody(3), userToken, KEY), 409, "IDEMPOTENCY_KEY_CONFLICT",
                "同じIdempotency-Keyに対する注文内容が競合しています", "/api/v1/me/orders");
        assertEquals(8, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(200, post("/api/v1/me/orders/" + id + "/cancel", Map.of(), userToken, null).statusCode());
        assertEquals(10, stock());

        assertEquals(200, post(INVENTORY_PATH, adjustment(5), adminToken, KEY).statusCode());
        assertProblem(post(INVENTORY_PATH, adjustment(6), adminToken, KEY), 409, "IDEMPOTENCY_KEY_CONFLICT",
                "同じIdempotency-Keyに対する在庫調整内容が競合しています", INVENTORY_PATH);
        assertEquals(15, stock());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Long.class);
        // 既存keyの見逃しを再現し、実driver/Hibernateのduplicate-key診断とrollbackを検証する。
        doReturn(Optional.empty()).when(movements).findByActorIdAndIdempotencyKey(adminId, KEY);
        assertProblem(post(INVENTORY_PATH, adjustment(5), adminToken, KEY), 409, "IDEMPOTENCY_KEY_CONFLICT",
                "同じIdempotency-Keyに対する在庫調整内容が競合しています", INVENTORY_PATH);
        assertEquals(15, stock());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Long.class));

        var another = post("/api/v1/me/orders", orderBody(1), userToken, "another-order");
        assertEquals(200, another.statusCode());
        long anotherId = json.readTree(another.body()).path("id").asLong();
        assertEquals(200, post("/api/v1/admin/orders/" + anotherId + "/ship", Map.of(), adminToken, null).statusCode());
        assertEquals(200, post("/api/v1/admin/orders/" + anotherId + "/complete", Map.of(), adminToken, null).statusCode());
        assertEquals(14, stock());
        assertProductionLogs(output, Set.of("order.create.completed", "order.cancel.completed", "order.ship.completed",
                "order.complete.completed", "inventory.adjustment.completed", "http.request.completed"));
    }

    @Test
    void unexpected5xxStillHasSafeDiagnosticsAndJwtRejectionStillReturns401(CapturedOutput output) throws Exception {
        doThrow(new DataIntegrityViolationException("SQL password=super-secret-password", new SQLException(
                "Duplicate entry 'alice-idempotency-secret-key' for key 'uk_users_username'", "23000", 1062)))
                .when(products).flush();
        var response = post(INVENTORY_PATH, adjustment(5), adminToken, KEY);
        assertProblem(response, 500, "SYSTEM_ERROR", ApiProblemFactory.SYSTEM_ERROR_DETAIL, INVENTORY_PATH);
        assertEquals(10, stock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class));
        var rejected = post(INVENTORY_PATH, adjustment(5), "very-secret-jwt", KEY);
        assertEquals(401, rejected.statusCode());
        assertEquals("UNAUTHENTICATED", json.readTree(rejected.body()).path("code").asText());
        var events = assertProductionLogs(output, Set.of("system.unhandled_error", "security.jwt.rejected", "http.request.completed"));
        var failure = events.stream().filter(event -> "system.unhandled_error".equals(event.path("event").asText())).findFirst().orElseThrow();
        assertEquals("ERROR", failure.path("level").asText());
        assertEquals(DataIntegrityViolationException.class.getName(), failure.path("exceptionType").asText());
        assertEquals(SQLException.class.getName(), failure.path("rootCauseType").asText());
        assertTrue(failure.hasNonNull("topFrame"));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), failure.path("requestId").asText());
        assertFalse(failure.has("stack_trace"));
    }

    private List<JsonNode> assertProductionLogs(CapturedOutput output, Set<String> required) throws Exception {
        // 実stdout/stderrを全logger対象で検査する。response/body/source literalは検査対象に混ぜない。
        String console = output.getAll();
        for (String value : List.of("alice", "race-user", KEY, "uk_users_username",
                "uk_inventory_movements_actor_idempotency_key", "Duplicate entry", PASSWORD, "very-secret-jwt")) {
            assertFalse(console.contains(value), "Production console must not contain sensitive DB/request values");
        }
        var records = new java.util.ArrayList<JsonNode>();
        for (String line : console.lines().toList()) {
            if (!line.startsWith("{")) continue;
            var record = json.readTree(line);
            assertNotEquals("org.mariadb.jdbc.message.server.ErrorPacket", record.path("logger_name").asText());
            assertNotEquals("org.hibernate.engine.jdbc.spi.SqlExceptionHelper", record.path("logger_name").asText());
            records.add(record);
        }
        for (String event : required) {
            var record = records.stream().filter(entry -> event.equals(entry.path("event").asText())).findFirst().orElseThrow();
            for (String field : List.of("@timestamp", "message", "level", "event", "requestId")) assertTrue(record.hasNonNull(field), field);
            assertEquals(record.path("requestId").asText(), java.util.UUID.fromString(record.path("requestId").asText()).toString());
        }
        return records;
    }

    private void assertProblem(HttpResponse<String> response, int status, String code, String detail, String path) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
        JsonNode body = json.readTree(response.body());
        assertEquals(status, body.path("status").asInt());
        assertEquals(code, body.path("code").asText());
        assertEquals(detail, body.path("detail").asText());
        assertEquals(path, body.path("instance").asText());
        assertEquals(ApiProblemFactory.TYPE_BASE + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'), body.path("type").asText());
        assertEquals(ApiProblemFactory.title(code), body.path("title").asText());
        assertEquals(6, body.size());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
    }

    private Map<String, Object> credentials(String username) { return Map.of("username", username, "password", PASSWORD); }
    private Map<String, Object> orderBody(int quantity) { return Map.of("items", List.of(Map.of("productId", 1, "quantity", quantity))); }
    private Map<String, Object> adjustment(int delta) { return Map.of("delta", delta, "reason", "RECEIPT"); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class); }
    private String token(String username) throws Exception {
        var response = post("/auth/login", credentials(username), null, null);
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).path("token").asText();
    }
    private HttpResponse<String> post(String path, Map<String, ?> body, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
