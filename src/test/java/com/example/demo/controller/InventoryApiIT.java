package com.example.demo.controller;

import com.example.demo.dto.*;
import com.example.demo.entity.InventoryMovement;
import com.example.demo.exception.InventoryAdjustmentConflictException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.InventoryService;
import com.example.demo.service.OrderService;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mariadb.MariaDBContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "spring.datasource.hikari.maximum-pool-size=30"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(InventoryApiIT.TimeConfiguration.class)
@Timeout(120)
class InventoryApiIT {
    private static final String BASE = "/api/v1/admin/products/";
    private static final String WHEN = "2026-10-10T01:02:03.123456Z";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("inventory_api");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary Clock inventoryTestClock() {
            return Clock.fixed(Instant.parse("2026-10-10T01:02:03.123456789Z"), ZoneOffset.UTC);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired InventoryService inventory;
    @Autowired OrderService orders;
    @Autowired EntityManager entityManager;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @SpyBean InventoryMovementRepository movements;

    @BeforeEach void fixtures() {
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES (1,'alice','private-test-hash','ROLE_USER'),(2,'admin','private-test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES (1,'apple',100,10,0),(2,'orange',200,5,0)");
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    String token(String username) { return "Bearer " + jwt.generateToken(username); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class); }
    Map<String, List<Map<String,Object>>> snapshot() {
        Map<String, List<Map<String,Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("products", "orders", "order_items", "inventory_movements"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    ResultActions adjustment(long id, String body) throws Exception {
        return mvc.perform(post(BASE + id + "/inventory-adjustments").header("Authorization", token("admin"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    String adjustmentBody(int delta, String reason, String note) throws Exception {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("delta", delta); body.put("reason", reason); body.put("note", note);
        return json.writeValueAsString(body);
    }
    ResultActions create(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/me/orders").header("Authorization", token("alice"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    String orderBody(int quantity) { return "{\"items\":[{\"productId\":1,\"quantity\":" + quantity + "}]}"; }
    long createOrder(int quantity) throws Exception {
        return json.readTree(create("order", orderBody(quantity)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").longValue();
    }

    @ParameterizedTest @CsvSource({"RECEIPT,10", "DAMAGE,-2", "STOCKTAKE,-1", "CORRECTION,3"})
    void manualReasonsPersistSafeResponseWithActualActorAndMicrosecondClock(String reason, int delta) throws Exception {
        String body = adjustment(1, adjustmentBody(delta, reason, "入荷・棚卸し")).andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1)).andExpect(jsonPath("$.delta").value(delta))
                .andExpect(jsonPath("$.stockBefore").value(10)).andExpect(jsonPath("$.stockAfter").value(10 + delta))
                .andExpect(jsonPath("$.reason").value(reason)).andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.sourceId").isEmpty()).andExpect(jsonPath("$.actor.id").value(2))
                .andExpect(jsonPath("$.actor.username").value("admin")).andExpect(jsonPath("$.note").value("入荷・棚卸し"))
                .andExpect(jsonPath("$.createdAt").value(WHEN)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("password")); assertFalse(body.contains("private-test-hash"));
        assertEquals(10 + delta, stock()); assertEquals(1, count("inventory_movements"));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
        assertEquals(123456000, jdbc.queryForObject("SELECT created_at FROM inventory_movements", java.sql.Timestamp.class).toLocalDateTime().getNano());
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("{\"delta\":null,\"reason\":\"RECEIPT\"}", "VALIDATION_ERROR"),
                Arguments.of("{\"delta\":0,\"reason\":\"RECEIPT\"}", "VALIDATION_ERROR"),
                Arguments.of("{\"delta\":1,\"reason\":null}", "VALIDATION_ERROR"),
                Arguments.of("{\"delta\":1,\"reason\":\"ORDER_CREATED\"}", "INVALID_REQUEST"),
                Arguments.of("{\"delta\":1,\"reason\":\"ORDER_CANCELLED\"}", "INVALID_REQUEST"),
                Arguments.of("{\"delta\":1,\"reason\":\"UNKNOWN\"}", "INVALID_REQUEST"),
                Arguments.of("{\"delta\":2147483648,\"reason\":\"RECEIPT\"}", "INVALID_REQUEST"),
                Arguments.of("{\"delta\":-2147483649,\"reason\":\"RECEIPT\"}", "INVALID_REQUEST"),
                Arguments.of("{", "INVALID_REQUEST"));
    }
    @ParameterizedTest @MethodSource("invalidInputs")
    void invalidInputsAre400AndLeaveAllTablesUnchanged(String body, String code) throws Exception {
        var before = snapshot();
        adjustment(1, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "　"})
    void blankNotesNormalizeToNull(String note) throws Exception {
        adjustment(1, adjustmentBody(1, "RECEIPT", note)).andExpect(status().isOk()).andExpect(jsonPath("$.note").isEmpty());
        assertNull(jdbc.queryForObject("SELECT note FROM inventory_movements", String.class));
    }
    @Test void noteBoundaryIs500CharactersAndDoesNotWriteAt501() throws Exception {
        adjustment(1, adjustmentBody(1, "RECEIPT", "a".repeat(500))).andExpect(status().isOk());
        var before = snapshot();
        adjustment(1, adjustmentBody(1, "RECEIPT", "a".repeat(501))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @CsvSource({"3,-5", "2147483647,1", "3,-2147483648"})
    void impossibleResultsReturnSafe409WithoutWrites(int initial, int delta) throws Exception {
        jdbc.update("UPDATE products SET stock=? WHERE id=1", initial);
        var before = snapshot();
        String body = adjustment(1, adjustmentBody(delta, "CORRECTION", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ADJUSTMENT_CONFLICT"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("ArithmeticException")); assertFalse(body.contains("SQL"));
        assertEquals(before, snapshot());
    }

    static Stream<Arguments> authorization() {
        return Stream.of("admin", "alice", "anonymous").flatMap(user ->
                Stream.of(true, false).flatMap(exists -> Stream.of("post", "get")
                        .map(method -> Arguments.of(user, exists, method))));
    }
    @ParameterizedTest @MethodSource("authorization")
    void authorizationPrecedesResourceLookupAndParsing(String user, boolean exists, String method) throws Exception {
        long id = exists ? 1 : 999;
        var request = method.equals("post") ? post(BASE + id + "/inventory-adjustments").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(user.equals("admin") ? adjustmentBody(1, "RECEIPT", null) : "{")
                : get(BASE + id + "/inventory-movements");
        if (!user.equals("anonymous")) request.header("Authorization", token(user));
        int expected = user.equals("anonymous") ? 401 : user.equals("alice") ? 403 : exists ? 200 : 404;
        var before = snapshot();
        var result = mvc.perform(request).andExpect(status().is(expected));
        if (expected == 404) result.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        if (!(expected == 200 && method.equals("post"))) assertEquals(before, snapshot());
    }

    @Test void creationKeepsInitialStockAndOnlyUpdateDtoRejectsUnknownFields() throws Exception {
        mvc.perform(post("/products").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new\",\"price\":100,\"stock\":8,\"futureField\":\"ignored\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(8));
        assertEquals(0, count("inventory_movements"));
        for (String extra : List.of("\"stock\":999", "\"futureField\":true")) {
            var before = snapshot();
            mvc.perform(put("/products/1").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"changed\",\"price\":200,\"version\":0," + extra + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            assertEquals(before, snapshot());
        }
    }
    @Test void metadataUpdatePreservesStockAndManualAdjustmentMakesOldVersionStale() throws Exception {
        adjustment(1, adjustmentBody(-2, "DAMAGE", null)).andExpect(status().isOk());
        var before = snapshot();
        mvc.perform(put("/products/1").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"changed\",\"price\":200,\"version\":0}")).andExpect(status().isConflict());
        assertEquals(before, snapshot());
        mvc.perform(put("/products/1").header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"changed\",\"price\":200,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(8)).andExpect(jsonPath("$.version").value(2));
        assertEquals(1, count("inventory_movements"));
    }

    @Test void historyIsProductScopedPaginatedAndHasFixedStableOrderForIdenticalTimestamps() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            ids.add(json.readTree(adjustment(1, adjustmentBody(1, "RECEIPT", null)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("id").longValue());
        mvc.perform(get(BASE + "2/inventory-movements").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        adjustment(2, adjustmentBody(2, "RECEIPT", null)).andExpect(status().isOk());
        mvc.perform(get(BASE + "1/inventory-movements?page=0&size=2&sort=id,asc").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(2))).andExpect(jsonPath("$.content[1].id").value(ids.get(1)));
        mvc.perform(get(BASE + "1/inventory-movements?page=1&size=2&sort=delta,asc").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(0)));
    }

    @Test void multiProductOrderWritesOneMovementPerProductReplayAndMismatchDoNotAppendAndOtherCommandsDoNotAppend() throws Exception {
        String body = "{\"items\":[{\"productId\":2,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}";
        long id = json.readTree(create("multi", body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").longValue();
        assertEquals(2, count("inventory_movements"));
        var rows = jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY product_id");
        for (var row : rows) {
            assertEquals("ORDER_CREATED", row.get("reason")); assertEquals("ORDER", row.get("source_type"));
            assertEquals(id, ((Number) row.get("source_id")).longValue()); assertEquals(1L, ((Number) row.get("actor_user_id")).longValue());
            assertNull(row.get("note"));
        }
        assertEquals(-2, ((Number) rows.get(0).get("delta")).intValue());
        assertEquals(-1, ((Number) rows.get(1).get("delta")).intValue());
        var before = snapshot();
        create("multi", body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(before, snapshot());
        create("multi", orderBody(3)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertEquals(before, snapshot());
        for (String command : List.of("ship", "complete"))
            mvc.perform(post("/api/v1/admin/orders/" + id + "/" + command).header("Authorization", token("admin"))).andExpect(status().isOk());
        assertEquals(2, count("inventory_movements")); assertEquals(8, stock());
    }

    @ParameterizedTest @ValueSource(strings = {"self", "admin"})
    void cancellationRecordsActualActorAndRestoresStockOnlyOnce(String mode) throws Exception {
        long id = createOrder(5);
        assertEquals(5, stock());
        String path = mode.equals("self") ? "/api/v1/me/orders/" : "/api/v1/admin/orders/";
        String actor = mode.equals("self") ? "alice" : "admin";
        mvc.perform(post(path + id + "/cancel").header("Authorization", token(actor))).andExpect(status().isOk());
        assertEquals(10, stock()); assertEquals(2, count("inventory_movements"));
        var movement = jdbc.queryForMap("SELECT * FROM inventory_movements WHERE reason='ORDER_CANCELLED'");
        assertEquals(5, ((Number) movement.get("delta")).intValue());
        assertEquals(5, ((Number) movement.get("stock_before")).intValue());
        assertEquals(10, ((Number) movement.get("stock_after")).intValue());
        assertEquals(id, ((Number) movement.get("source_id")).longValue());
        assertEquals(mode.equals("self") ? 1L : 2L, ((Number) movement.get("actor_user_id")).longValue());
        var before = snapshot();
        mvc.perform(post(path + id + "/cancel").header("Authorization", token(actor))).andExpect(status().isConflict());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"stock", "money", "missing", "quantity"})
    void failedOrderDoesNotLeaveInventoryHistoryOrOtherWrites(String failure) throws Exception {
        if (failure.equals("money")) jdbc.update("UPDATE products SET price=? WHERE id=1", Long.MAX_VALUE);
        String body = failure.equals("missing") ? "{\"items\":[{\"productId\":999,\"quantity\":1}]}"
                : orderBody(failure.equals("stock") ? 11 : failure.equals("quantity") ? 0 : 2);
        var before = snapshot();
        create("failure", body).andExpect(status().is4xxClientError());
        assertEquals(before, snapshot());
    }
    @Test void failedCancelOverflowPreservesStatusStockAndAllHistory() throws Exception {
        long id = createOrder(5);
        adjustment(1, adjustmentBody(Integer.MAX_VALUE - 5, "RECEIPT", null)).andExpect(status().isOk());
        var before = snapshot();
        mvc.perform(post("/api/v1/admin/orders/" + id + "/cancel").header("Authorization", token("admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVENTORY_RESTORATION_FAILED"));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"manual", "order", "cancel"})
    void movementInsertFailureAfterFlushRollsBackTheEntireBusinessTransaction(String operation) throws Exception {
        long id = operation.equals("cancel") ? createOrder(5) : 0;
        var before = snapshot();
        AtomicInteger inserted = new AtomicInteger();
        doAnswer(invocation -> {
            InventoryMovement movement = invocation.getArgument(0);
            entityManager.persist(movement);
            entityManager.flush();
            inserted.set(count("inventory_movements"));
            throw new DataIntegrityViolationException("private-password SQL insert failure");
        }).when(movements).save(any(InventoryMovement.class));
        ResultActions response = switch (operation) {
            case "manual" -> adjustment(1, adjustmentBody(1, "RECEIPT", null));
            case "order" -> create("rollback", orderBody(2));
            default -> mvc.perform(post("/api/v1/admin/orders/" + id + "/cancel").header("Authorization", token("admin")));
        };
        String body = response.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(operation.equals("cancel") ? 2 : 1, inserted.get());
        assertFalse(body.contains("private-password")); assertFalse(body.contains("SQL"));
        assertEquals(before, snapshot());
    }

    void authenticate(String user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                AuthorityUtils.createAuthorityList(user.equals("admin") ? "ROLE_ADMIN" : "ROLE_USER")));
    }
    InventoryAdjustmentRequest request(int delta) {
        var request = new InventoryAdjustmentRequest(); request.setDelta(delta); request.setReason(ManualInventoryReason.DAMAGE); return request;
    }
    void authenticated(String user, Runnable action) {
        authenticate(user);
        try { action.run(); } finally { SecurityContextHolder.clearContext(); }
    }
    List<Throwable> concurrent(List<Runnable> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size()), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable action : actions) futures.add(executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(30, TimeUnit.SECONDS));
                try { action.run(); return null; } catch (RuntimeException failure) { return failure; }
            }));
            assertTrue(ready.await(30, TimeUnit.SECONDS)); start.countDown();
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    void assertChain(int initial, int expected, int size) {
        var rows = jdbc.queryForList("SELECT stock_before,delta,stock_after FROM inventory_movements WHERE product_id=1 ORDER BY created_at,id");
        assertEquals(size, rows.size());
        int current = initial;
        for (var row : rows) {
            assertEquals(current, ((Number) row.get("stock_before")).intValue());
            current += ((Number) row.get("delta")).intValue();
            assertEquals(current, ((Number) row.get("stock_after")).intValue());
        }
        assertEquals(expected, current); assertEquals(expected, stock());
    }
    @Test void twentyConcurrentManualChangesHaveNoLostUpdatesAndContiguousHistory() throws Exception {
        jdbc.update("UPDATE products SET stock=100 WHERE id=1");
        var results = concurrent(Collections.nCopies(20, () -> authenticated("admin", () -> inventory.adjust(1L, request(-1), UUID.randomUUID().toString()))));
        assertTrue(results.stream().allMatch(Objects::isNull), results.toString());
        assertChain(100, 80, 20);
        assertEquals(20L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
    }
    @Test void concurrentOrderAndDamageCannotBothConsumeMoreThanAvailableStock() throws Exception {
        var results = concurrent(List.of(
                () -> authenticated("alice", () -> orders.createOrder(com.example.demo.TestOrders.request(1L, 7), "concurrent")),
                () -> authenticated("admin", () -> inventory.adjust(1L, request(-4), "damage"))));
        assertEquals(1, results.stream().filter(Objects::isNull).count());
        Throwable failure = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertTrue(failure instanceof InventoryAdjustmentConflictException || failure instanceof OutOfStockException, failure.toString());
        assertChain(10, count("orders") == 1 ? 3 : 6, 1);
    }
    @Test void concurrentManualAndCancellationBothUseCurrentStock() throws Exception {
        long id = createOrder(5);
        var results = concurrent(List.of(
                () -> authenticated("admin", () -> orders.cancelOrder(id)),
                () -> authenticated("admin", () -> inventory.adjust(1L, request(-2), "damage"))));
        assertTrue(results.stream().allMatch(Objects::isNull), results.toString());
        assertChain(10, 8, 3);
    }

    @Test void auditForeignKeyRestrictsProductDeletionWithSafe409Contract() throws Exception {
        adjustment(1, adjustmentBody(1, "RECEIPT", null)).andExpect(status().isOk());
        var before = snapshot();
        mvc.perform(delete("/products/1").header("Authorization", token("admin")))
                .andExpect(com.example.demo.TestApiProblems.problem(409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict", "/products/1"))
                .andExpect(jsonPath("$.detail").value("注文履歴または在庫履歴から参照されているため商品を削除できません"));
        assertEquals(before, snapshot());
    }
    @Test void persistedHistoryCannotBeUpdatedThroughHibernateDirtyChecking() throws Exception {
        long id = json.readTree(adjustment(1, adjustmentBody(1, "RECEIPT", "original")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").longValue();
        var before = snapshot();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            InventoryMovement movement = entityManager.find(InventoryMovement.class, id);
            try {
                var note = InventoryMovement.class.getDeclaredField("note");
                note.setAccessible(true); note.set(movement, "accidental change");
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            entityManager.flush();
        });
        assertEquals(before, snapshot());
        for (var method : InventoryMovement.class.getMethods()) assertFalse(method.getName().startsWith("set"));
    }
    @Test void localOpenApiDescribesInventoryAndMetadataOnlyProductUpdate() throws Exception {
        JsonNode document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var schemas = document.path("components").path("schemas");
        assertFalse(schemas.path("ProductUpdateRequest").path("properties").has("stock"));
        assertFalse(schemas.path("ProductUpdateRequest").path("additionalProperties").asBoolean(true));
        assertEquals(500, schemas.path("InventoryAdjustmentRequest").path("properties").path("note").path("maxLength").asInt());
        assertEquals(4, schemas.path("InventoryAdjustmentRequest").path("properties").path("reason").path("enum").size());
        for (String operation : List.of("inventory-adjustments", "inventory-movements")) {
            String method = operation.equals("inventory-adjustments") ? "post" : "get";
            var api = document.path("paths").path(BASE + "{id}/" + operation).path(method);
            assertFalse(api.isMissingNode());
            for (String code : List.of("401", "403", "404"))
                assertTrue(api.path("responses").has(code), operation + ":" + code);
            assertTrue(api.toString().contains("PRODUCT_NOT_FOUND"));
            if (method.equals("post")) {
                assertTrue(api.path("responses").has("400")); assertTrue(api.path("responses").has("409"));
                assertTrue(api.toString().contains("INVENTORY_ADJUSTMENT_CONFLICT"));
            }
        }
    }
}
