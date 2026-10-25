package com.example.demo.controller;

import com.example.demo.TestAuth;
import com.example.demo.dto.OrderRequest;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OrderTimestampPrecisionIT.TimeConfiguration.class)
class OrderTimestampPrecisionIT {
    private static final String ME = "/api/v1/me/orders";
    private static final String ADMIN = "/api/v1/admin/orders";
    private static final String BODY = "{\"items\":[{\"productId\":1,\"quantity\":2}]}";
    private static final Instant CREATE = Instant.parse("2026-10-25T01:02:03.123456789Z");
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(CREATE);
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("order_precision").withEnv("TZ", "UTC").withCommand("--default-time-zone=+00:00");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary
        Clock scenarioClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return NOW.get(); }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @MockitoSpyBean OrderService orders;
    @LocalServerPort int port;

    @BeforeEach
    void fixture() {
        NOW.set(CREATE);
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES "
                + "(UUID_v4(),1,'alice','unused-test-hash','ROLE_USER'),"
                + "(UUID_v4(),2,'admin','unused-test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES (1,'apple',100,10,0)");
        // Static mocks are thread-local: scope only Instant.now() on the real HTTP business worker.
        // Authentication, repositories, transactions, callbacks, DTOs and HTTP serialization stay real.
        doAnswer(this::atScenarioTime).when(orders).createOrderResponse(any(OrderRequest.class), anyString());
        doAnswer(this::atScenarioTime).when(orders).shipOrderResponse(anyLong());
        doAnswer(this::atScenarioTime).when(orders).completeOrderResponse(anyLong());
        doAnswer(this::atScenarioTime).when(orders).cancelMyOrderResponse(anyLong());
    }

    private Object atScenarioTime(InvocationOnMock call) throws Throwable {
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenAnswer(ignored -> NOW.get());
            return call.callRealMethod();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-25T01:02:03.123456789Z,2026-10-25T01:02:03.123456Z",
            "2026-10-25T01:02:03.999999999Z,2026-10-25T01:02:03.999999Z",
            "2026-10-25T01:02:03.123456Z,2026-10-25T01:02:03.123456Z"
    })
    void initialReplayAndGetMatchActualStoredValuesWithoutTestNormalization(String source, String expected) throws Exception {
        NOW.set(Instant.parse(source));
        JsonNode first = request("POST", ME, BODY, "alice", "precision", 200);
        long id = first.path("id").asLong();
        Stage created = stage("create", first, id);
        Stage replay = stage("replay", request("POST", ME, BODY, "alice", "precision", 200), id);
        Stage get = stage("get", request("GET", ME + "/" + id, null, "alice", null, 200), id);
        assertAll(() -> timestamps(created, expected, expected, null),
                () -> timestamps(replay, expected, expected, null),
                () -> timestamps(get, expected, expected, null),
                () -> sameDates(created, replay), () -> sameDates(created, get));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
        assertEquals(1, count("inventory_movements"));
        assertEquals(8, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
    }

    @Test
    void shipAndCompleteKeepCreationTimeAndReplayTheCurrentResource() throws Exception {
        JsonNode first = request("POST", ME, BODY, "alice", "state", 200);
        long id = first.path("id").asLong();
        Stage created = stage("state-create", first, id);
        NOW.set(Instant.parse("2026-10-25T01:02:04.654321987Z"));
        Stage shipped = stage("ship", request("POST", ADMIN + "/" + id + "/ship", null, "admin", null, 200), id);
        Stage shipGet = stage("ship-get", request("GET", ME + "/" + id, null, "alice", null, 200), id);
        Stage shipReplay = stage("ship-replay", request("POST", ME, BODY, "alice", "state", 200), id);
        NOW.set(Instant.parse("2026-10-25T01:02:05.999999999Z"));
        Stage completed = stage("complete", request("POST", ADMIN + "/" + id + "/complete", null, "admin", null, 200), id);
        Stage completeGet = stage("complete-get", request("GET", ME + "/" + id, null, "alice", null, 200), id);
        Stage completeReplay = stage("complete-replay", request("POST", ME, BODY, "alice", "state", 200), id);
        var unchanged = jdbc.queryForList("SELECT * FROM orders WHERE id=?", id);
        request("POST", ME, "{\"items\":[{\"productId\":1,\"quantity\":3}]}", "alice", "state", 409);
        request("POST", ADMIN + "/" + id + "/complete", null, "admin", null, 409);
        assertEquals(unchanged, jdbc.queryForList("SELECT * FROM orders WHERE id=?", id));
        assertEquals("SHIPPED", shipReplay.http.path("status").asText());
        assertEquals("COMPLETED", completeReplay.http.path("status").asText());
        assertAll(() -> timestamps(created, "2026-10-25T01:02:03.123456Z", "2026-10-25T01:02:03.123456Z", null),
                () -> timestamps(shipped, "2026-10-25T01:02:03.123456Z", "2026-10-25T01:02:04.654321Z", null),
                () -> timestamps(completed, "2026-10-25T01:02:03.123456Z", "2026-10-25T01:02:05.999999Z", "2026-10-25T01:02:05.999999Z"),
                () -> sameDates(shipped, shipGet), () -> sameDates(shipped, shipReplay),
                () -> sameDates(completed, completeGet), () -> sameDates(completed, completeReplay));
    }

    @Test
    void cancellationKeepsOrderedAtAndRejectedCommandsDoNotChangeStoredDates() throws Exception {
        JsonNode first = request("POST", ME, BODY, "alice", "cancel", 200);
        long id = first.path("id").asLong();
        NOW.set(Instant.parse("2026-10-25T01:02:06.000001999Z"));
        Stage cancelled = stage("cancel", request("POST", ME + "/" + id + "/cancel", null, "alice", null, 200), id);
        Stage get = stage("cancel-get", request("GET", ME + "/" + id, null, "alice", null, 200), id);
        Stage replay = stage("cancel-replay", request("POST", ME, BODY, "alice", "cancel", 200), id);
        var unchanged = jdbc.queryForList("SELECT * FROM orders WHERE id=?", id);
        request("POST", ME + "/" + id + "/cancel", null, "alice", null, 409);
        request("GET", ME + "/" + id, null, null, null, 401);
        request("POST", ADMIN + "/" + id + "/ship", null, "alice", null, 403);
        assertEquals(unchanged, jdbc.queryForList("SELECT * FROM orders WHERE id=?", id));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
        assertAll(() -> timestamps(cancelled, "2026-10-25T01:02:03.123456Z", "2026-10-25T01:02:06.000001Z", null),
                () -> sameDates(cancelled, get), () -> sameDates(cancelled, replay));
    }

    @Test
    void legacyCompletedRowsWithUnknownCompletionTimeAreReadWithoutBackfill() throws Exception {
        legacy("COMPLETED");
        var before = jdbc.queryForList("SELECT * FROM orders WHERE id=900");
        Stage get = stage("legacy-completed", request("GET", ME + "/900", null, "alice", null, 200), 900);
        timestamps(get, "2025-01-02T03:04:05.123456Z", "2025-01-02T03:04:06.654321Z", null);
        assertEquals(before, jdbc.queryForList("SELECT * FROM orders WHERE id=900"));
    }

    @Test
    void legacyPendingRowsKeepCreationTimeAndNullIdempotencyMetadataWhenUpdated() throws Exception {
        legacy("PENDING");
        NOW.set(Instant.parse("2026-10-25T01:02:07.111111999Z"));
        Stage shipped = stage("legacy-ship", request("POST", ADMIN + "/900/ship", null, "admin", null, 200), 900);
        Stage get = stage("legacy-ship-get", request("GET", ME + "/900", null, "alice", null, 200), 900);
        assertAll(() -> timestamps(shipped, "2025-01-02T03:04:05.123456Z", "2026-10-25T01:02:07.111111Z", null),
                () -> sameDates(shipped, get));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id=900 AND "
                + "(idempotency_key IS NOT NULL OR request_fingerprint IS NOT NULL)", Integer.class));
    }

    @Test
    void schemaAndDatabasePrecisionAreSixDigitsWithAnExplicitUtcSession() {
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        var columns = jdbc.queryForList("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' "
                + "AND COLUMN_NAME IN ('ordered_at','updated_at','completed_at')", Integer.class);
        assertEquals(List.of(6, 6, 6), columns);
        String mode = jdbc.queryForObject("SELECT @@sql_mode", String.class);
        assertFalse(mode.contains("TIME_ROUND_FRACTIONAL"));
        String stored = jdbc.queryForObject("SELECT DATE_FORMAT(CAST(? AS DATETIME(6)), '%Y-%m-%dT%H:%i:%s.%fZ')",
                String.class, "2026-10-25 01:02:03.999999999");
        assertEquals("2026-10-25T01:02:03.999999Z", stored);
        System.out.printf("ISSUE159 environment jvmZone=%s dbSession=UTC columns=DATETIME(6) dbCast=%s%n",
                ZoneId.systemDefault(), stored);
    }

    private void legacy(String status) {
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,version) "
                + "VALUES(900,1,200,?,'2025-01-02 03:04:05.123456','2025-01-02 03:04:06.654321',0)", status);
        jdbc.update("INSERT INTO order_items(id,quantity,price,product_name_snapshot,order_id,product_id) "
                + "VALUES(900,2,100,'apple',900,1)");
    }

    private HttpResponse<String> send(String method, String path, String body, String actor, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (actor != null) builder.header("Authorization", "Bearer " + TestAuth.token(jwt, jdbc, actor));
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode request(String method, String path, String body, String actor, String key, int status) throws Exception {
        var response = send(method, path, body, actor, key);
        assertEquals(status, response.statusCode());
        JsonNode result = json.readTree(response.body());
        if (status >= 400) {
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
            assertEquals(6, result.size());
            assertEquals(status, result.path("status").asInt());
            assertFalse(result.path("detail").asText().contains("Exception"));
        }
        return result;
    }

    private record Stage(JsonNode http, Map<String, Object> db) { }

    private Stage stage(String phase, JsonNode response, long id) {
        var db = jdbc.queryForMap("SELECT DATE_FORMAT(ordered_at,'%Y-%m-%dT%H:%i:%s.%fZ') orderedAt,"
                + "DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s.%fZ') updatedAt,"
                + "DATE_FORMAT(completed_at,'%Y-%m-%dT%H:%i:%s.%fZ') completedAt FROM orders WHERE id=?", id);
        for (String field : List.of("orderedAt", "updatedAt", "completedAt")) {
            String text = response.path(field).isNull() ? null : response.path(field).asText();
            System.out.printf("ISSUE159 phase=%s field=%s http=%s httpNanos=%s db=%s%n", phase, field, text,
                    text == null ? null : Instant.parse(text).getNano(), db.get(field));
        }
        return new Stage(response, db);
    }

    private void timestamps(Stage stage, String ordered, String updated, String completed) {
        assertAll(() -> timestamp(stage, "orderedAt", ordered), () -> timestamp(stage, "updatedAt", updated),
                () -> timestamp(stage, "completedAt", completed));
    }

    private void timestamp(Stage stage, String field, String expected) {
        if (expected == null) {
            assertTrue(stage.http.path(field).isNull());
            assertNull(stage.db.get(field));
            return;
        }
        String text = stage.http.path(field).asText();
        Instant actual = Instant.parse(text);
        Instant stored = Instant.parse((String) stage.db.get(field));
        assertEquals(Instant.parse(expected), actual);
        assertEquals(stored, actual); // Compare actual values directly: never truncate either side.
        assertEquals(0, actual.getNano() % 1000);
    }

    private void sameDates(Stage first, Stage second) {
        assertEquals(first.http.path("id"), second.http.path("id"));
        for (String field : List.of("orderedAt", "updatedAt", "completedAt")) {
            assertEquals(first.http.path(field), second.http.path(field)); // Original JSON timestamp strings.
            assertEquals(first.db.get(field), second.db.get(field));
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
