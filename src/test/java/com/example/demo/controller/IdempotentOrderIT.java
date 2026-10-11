package com.example.demo.controller;

import com.example.demo.repository.OrderRepository;
import com.example.demo.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "spring.datasource.hikari.maximum-pool-size=30"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class IdempotentOrderIT {
    private static final String ME = "/api/v1/me/orders";
    private static final String ADMIN = "/api/v1/admin/orders";
    private static final String SECRET = Base64.getEncoder()
            .encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("idempotent_order");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwt;
    @MockitoSpyBean private OrderRepository orders;

    @BeforeEach
    void fixtures() {
        assertEquals(0, count("orders"));
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER'),(2,'bob','test-hash','ROLE_USER'),(3,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id,name,price,stock,version) VALUES (1,'apple',100,10,0),(2,'orange',200,20,0)");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void tenSequentialRetriesLeaveOneOrderAndOneStockReduction() throws Exception {
        JsonNode first = successful("alice", "retry", body(3));
        assertEquals(7, stock());
        var saved = snapshot();
        for (int retry = 0; retry < 10; retry++) assertEquals(persistedPrecision(first), persistedPrecision(successful("alice", "retry", body(3))));
        assertEquals(saved, snapshot());
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
        assertEquals(1, count("inventory_movements"));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
    }

    @Test
    void reversedItemsAndJsonWhitespaceReplayTheSameCanonicalOrder() throws Exception {
        String forward = "{\"items\":[{\"productId\":1,\"quantity\":2},{\"productId\":2,\"quantity\":3}]}";
        String reversed = "{ \"items\" : [ {\"quantity\":3,\"productId\":2}, {\"quantity\":2,\"productId\":1} ] }";
        JsonNode first = successful("alice", "reversed", forward);
        var saved = snapshot();
        assertEquals(persistedPrecision(first), persistedPrecision(successful("alice", "reversed", reversed)));
        assertEquals(saved, snapshot());
        assertEquals(2, count("order_items"));
        assertEquals(2, count("inventory_movements"));
        assertEquals(List.of(8, 17), jdbc.queryForList("SELECT stock FROM products ORDER BY id", Integer.class));
        assertEquals("163affc78005025f8d4a3f1856d60e8b958dc87fc8b4ec9750425806d10eb4eb",
                jdbc.queryForObject("SELECT request_fingerprint FROM orders", String.class));
    }

    @Test
    void differentQuantityOrProductIs409WithoutAnyChanges() throws Exception {
        successful("alice", "conflict", body(2));
        var saved = snapshot();
        for (String different : List.of(body(3), "{\"items\":[{\"productId\":2,\"quantity\":2}]}")) {
            create("alice", "conflict", different).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
            assertEquals(saved, snapshot());
        }
    }

    @Test
    void priceAndStockChangesDoNotAlterOriginalSnapshotsOrRequireAvailableStockForReplay() throws Exception {
        JsonNode original = successful("alice", "snapshot", body(3));
        mvc.perform(put("/products/1").header("Authorization", token("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"changed\",\"price\":200,\"version\":1}"))
                .andExpect(status().isOk());
        successful("bob", "another-order", body(7));
        assertEquals(0, stock());
        var saved = snapshot();
        assertEquals(persistedPrecision(original), persistedPrecision(successful("alice", "snapshot", body(3))));
        assertEquals(100, original.path("items").get(0).path("unitPrice").longValue());
        assertEquals(300, original.path("totalPrice").longValue());
        assertEquals(saved, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ship", "cancel", "complete"})
    void retryReturnsCurrentStateAndMetadataStaysImmutableThroughCommands(String command) throws Exception {
        long id = successful("alice", "state", body(3)).path("id").longValue();
        var metadata = jdbc.queryForMap("SELECT idempotency_key,request_fingerprint FROM orders WHERE id=?", id);
        if (command.equals("complete")) command(id, "ship").andExpect(status().isOk());
        command(id, command).andExpect(status().isOk());
        var saved = snapshot();
        String expected = Map.of("ship", "SHIPPED", "cancel", "CANCELLED", "complete", "COMPLETED").get(command);
        JsonNode replay = successful("alice", "state", body(3));
        assertEquals(id, replay.path("id").longValue());
        assertEquals(expected, replay.path("status").asText());
        assertEquals(saved, snapshot());
        assertEquals(metadata, jdbc.queryForMap("SELECT idempotency_key,request_fingerprint FROM orders WHERE id=?", id));
    }

    @Test
    void keyIsScopedToCustomerAndCannotRevealAnotherCustomersOrder() throws Exception {
        JsonNode alice = successful("alice", "shared", body(2));
        JsonNode bob = successful("bob", "shared", body(3));
        assertNotEquals(alice.path("id"), bob.path("id"));
        assertEquals(1, alice.path("customer").path("id").longValue());
        assertEquals(2, bob.path("customer").path("id").longValue());
        assertEquals(persistedPrecision(bob), persistedPrecision(successful("bob", "shared", body(3))));
        mvc.perform(get(ME + "/" + alice.path("id").longValue()).header("Authorization", token("bob")))
                .andExpect(status().isNotFound());
        assertEquals(5, stock());
        assertEquals(2, count("orders"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " leading", "trailing ", "line\nbreak", "carriage\rreturn",
            "control\u0001", "tab\t", "slash/key", "日本語", "key,key"})
    void invalidOrMissingKeyIs400AndLeavesAllDataUnchanged(String key) throws Exception {
        var before = snapshot();
        String code = key != null && key.chars().anyMatch(c -> c == '\n' || c == '\r' || c == 1)
                ? "INVALID_REQUEST" : "INVALID_IDEMPOTENCY_KEY";
        create("alice", key, body(3)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
        assertEquals(before, snapshot());
    }

    @Test
    void maximumLengthAndAllowedAsciiAreAcceptedButOverLengthIs400() throws Exception {
        successful("alice", "A".repeat(128), body(1));
        successful("alice", "AZaz09._:-", body(1));
        var before = snapshot();
        create("alice", "A".repeat(129), body(1)).andExpect(status().isBadRequest());
        assertEquals(before, snapshot());
    }

    @Test
    void caseSensitiveKeysAreDifferentOrders() throws Exception {
        assertNotEquals(successful("alice", "KeyA", body(2)).path("id"),
                successful("alice", "keya", body(2)).path("id"));
        assertEquals(2, count("orders"));
        assertEquals(6, stock());
    }

    @Test
    void duplicateProductIdsRemain400InsteadOfBeingMergedOrReplayed() throws Exception {
        successful("alice", "duplicate", body(3));
        var saved = snapshot();
        create("alice", "duplicate", "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
        assertEquals(saved, snapshot());
    }

    @Test
    void anonymousIs401AdminSelfCreateWorksAndAdminCommandsNeedNoKey() throws Exception {
        mvc.perform(post(ME).contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isUnauthorized());
        assertEquals(0, count("orders"));
        long id = successful("admin", "admin-self", body(1)).path("id").longValue();
        command(id, "ship").andExpect(status().isOk());
        command(id, "complete").andExpect(status().isOk());
        mvc.perform(get(ADMIN).header("Authorization", token("admin"))).andExpect(status().isOk());
    }

    @Test
    void failureDoesNotConsumeKeyAndRetryAfterRestockCreatesExactlyOneOrder() throws Exception {
        var before = snapshot();
        create("alice", "recover", body(11)).andExpect(status().isBadRequest());
        assertEquals(before, snapshot());
        jdbc.update("UPDATE products SET stock=20 WHERE id=1");
        successful("alice", "recover", body(11));
        assertEquals(1, count("orders"));
        assertEquals(9, stock());
    }

    @Test
    void specificUniqueViolationIs409AndRollsBackAttemptedStockChangesWithoutReplaying() throws Exception {
        successful("alice", "unique-defense", body(2));
        var saved = snapshot();
        // 別writer/将来のlookup欠落を模擬。実MariaDBの1062でTransaction rollbackを検証する。
        doReturn(Optional.empty()).when(orders).findByCustomerIdAndIdempotencyKey(1L, "unique-defense");
        create("alice", "unique-defense", body(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertEquals(saved, snapshot());
    }

    @Test
    void legacyNullMetadataOrdersRemainReadableAndCanShipCompleteAndCancel() throws Exception {
        for (long id : List.of(101L, 102L)) {
            jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version) VALUES (?,1,200,'PENDING','2000-01-01','2000-01-01',0)", id);
            jdbc.update("INSERT INTO order_items (quantity,price,product_name_snapshot,order_id,product_id) VALUES (2,100,'legacy',?,1)", id);
            mvc.perform(get(ME + "/" + id).header("Authorization", token("alice"))).andExpect(status().isOk());
        }
        command(101, "ship").andExpect(status().isOk());
        command(101, "complete").andExpect(status().isOk());
        command(102, "cancel").andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key IS NULL AND request_fingerprint IS NULL", Integer.class));
        assertEquals(12, stock());
    }

    @Test
    void responseAndOpenApiExposeOnlyRequiredHeaderNotInternalMetadata() throws Exception {
        JsonNode created = successful("alice", "private", body(1));
        long id = created.path("id").longValue();
        for (String path : List.of(ME, ME + "/" + id, ADMIN, ADMIN + "/" + id)) {
            String result = mvc.perform(get(path).header("Authorization", token(path.startsWith(ADMIN) ? "admin" : "alice")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(result.contains("idempotencyKey"));
            assertFalse(result.contains("requestFingerprint"));
        }
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String schemas = document.path("components").path("schemas").toString();
        assertFalse(schemas.contains("idempotencyKey"));
        assertFalse(schemas.contains("requestFingerprint"));
        var header = document.path("paths").path(ME).path("post").path("parameters").get(0);
        assertEquals("Idempotency-Key", header.path("name").asText());
        assertEquals("header", header.path("in").asText());
        assertTrue(header.path("required").asBoolean());
        assertTrue(header.path("description").asText().contains("customer"));
        assertTrue(header.path("description").asText().contains("409"));
        assertFalse(header.path("example").asText().isBlank());
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            // Issue #43 adds the required header only to manual adjustment POST; all other operations remain unchanged.
            if (operation.getKey().equals("post") && List.of(ME,
                    "/api/v1/admin/products/{id}/inventory-adjustments").contains(path.getKey())) return;
            operation.getValue().path("parameters").forEach(parameter ->
                    assertNotEquals("Idempotency-Key", parameter.path("name").asText()));
        }));
    }

    @RepeatedTest(3)
    void twentyConcurrentSameRequestsAllReturnOneOrderAndReduceStockOnce() throws Exception {
        var requests = Collections.nCopies(20, new Request("alice", "concurrent", body(3)));
        var results = concurrent(requests);
        long id = results.get(0).body.path("id").longValue();
        for (Outcome result : results) {
            assertEquals(200, result.status, result.body.toString());
            assertEquals(id, result.body.path("id").longValue());
        }
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
        assertEquals(1, count("inventory_movements"));
        assertEquals(id, jdbc.queryForObject("SELECT source_id FROM inventory_movements", Long.class));
        assertEquals(7, stock());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
    }

    @Test
    void tenOfEachBodyConcurrentlyYieldTenReplaysAndTenConflictsForEitherWinner() throws Exception {
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) requests.add(new Request("alice", "mixed", body(i % 2 == 0 ? 2 : 3)));
        var results = concurrent(requests);
        int winnerQuantity = jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class);
        long orderId = jdbc.queryForObject("SELECT id FROM orders", Long.class);
        for (int i = 0; i < results.size(); i++) {
            Outcome result = results.get(i);
            if ((i % 2 == 0 ? 2 : 3) == winnerQuantity) {
                assertEquals(200, result.status, result.body.toString());
                assertEquals(orderId, result.body.path("id").longValue());
            } else {
                assertEquals(409, result.status, result.body.toString());
                assertEquals("IDEMPOTENCY_KEY_CONFLICT", result.body.path("code").asText());
            }
        }
        assertEquals(10, results.stream().filter(result -> result.status == 200).count());
        assertEquals(10, results.stream().filter(result -> result.status == 409).count());
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_items"));
        assertEquals(1, count("inventory_movements"));
        assertEquals(10 - winnerQuantity, stock());
    }

    @Test
    void differentKeysForSameCustomerConcurrentlyCreateTwoOrders() throws Exception {
        assertTwoOrders(concurrent(List.of(new Request("alice", "first", body(2)), new Request("alice", "second", body(3)))));
    }

    @Test
    void sameKeyForDifferentCustomersConcurrentlyCreatesTwoOrdersWithSafeStock() throws Exception {
        assertTwoOrders(concurrent(List.of(new Request("alice", "same-key", body(2)), new Request("bob", "same-key", body(3)))));
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT customer_id FROM orders ORDER BY customer_id", Long.class));
    }

    private void assertTwoOrders(List<Outcome> results) {
        results.forEach(result -> assertEquals(200, result.status, result.body.toString()));
        assertNotEquals(results.get(0).body.path("id"), results.get(1).body.path("id"));
        assertEquals(2, count("orders"));
        assertEquals(2, count("order_items"));
        assertEquals(5, stock());
    }

    private List<Outcome> concurrent(List<Request> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size()), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (Request request : requests) {
                String bearer = token(request.user);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var response = mvc.perform(post(ME).header("Authorization", bearer).header("Idempotency-Key", request.key)
                            .contentType(MediaType.APPLICATION_JSON).content(request.body)).andReturn().getResponse();
                    return new Outcome(response.getStatus(), json.readTree(response.getContentAsString()));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record Request(String user, String key, String body) { }
    private record Outcome(int status, JsonNode body) { }
    private JsonNode persistedPrecision(JsonNode response) {
        // 初回の既存Instant.nano精度とDB DATETIME(6)の差だけを正規化し、全業務fieldを比較する。
        ObjectNode normalized = response.deepCopy();
        for (String field : List.of("orderedAt", "updatedAt"))
            normalized.put(field, Instant.parse(response.path(field).asText()).truncatedTo(ChronoUnit.MICROS).toString());
        return normalized;
    }
    private String token(String username) { return "Bearer " + jwt.generateToken(username); }
    private String body(int quantity) { return "{\"items\":[{\"productId\":1,\"quantity\":" + quantity + "}]}"; }
    private ResultActions create(String user, String key, String body) throws Exception {
        var request = post(ME).header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }
    private JsonNode successful(String user, String key, String body) throws Exception {
        var result = json.readTree(create(user, key, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(result.has("idempotencyKey"));
        assertFalse(result.has("requestFingerprint"));
        return result;
    }
    private ResultActions command(long id, String operation) throws Exception {
        return mvc.perform(post(ADMIN + "/" + id + "/" + operation).header("Authorization", token("admin")));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
                "items", jdbc.queryForList("SELECT * FROM order_items ORDER BY id"),
                "products", jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                "users", jdbc.queryForList("SELECT * FROM users ORDER BY id"));
    }
}
