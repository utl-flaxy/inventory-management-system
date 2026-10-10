package com.example.demo.controller;

import com.example.demo.dto.InventoryAdjustmentRequest;
import com.example.demo.dto.ManualInventoryReason;
import com.example.demo.entity.InventoryMovement;
import com.example.demo.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.example.demo.repository.*;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.InventoryService;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "spring.datasource.hikari.maximum-pool-size=30"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class InventoryAdjustmentIdempotencyIT {
    private static final String BASE = "/api/v1/admin/products/";
    private static final String PATH = BASE + "1/inventory-adjustments";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("inventory_idempotency_api");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired InventoryService inventory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;
    @SpyBean InventoryMovementRepository movements;
    @SpyBean UserRepository users;
    @SpyBean ProductRepository products;
    private Map<String,String> tokens;

    @BeforeEach void fixtures() {
        SecurityContextHolder.clearContext();
        for (String table : List.of("inventory_movements","order_items","orders","products","users")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES "
                + "(1,'alice','private-test-hash','ROLE_USER'),(2,'admin','private-test-hash','ROLE_ADMIN'),(3,'other','private-test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'apple',100,10,0),(2,'orange',100,5,0)");
        tokens = Map.of("alice","Bearer " + jwt.generateToken("alice"), "admin","Bearer " + jwt.generateToken("admin"),
                "other","Bearer " + jwt.generateToken("other"));
        clearInvocations(products, movements, users);
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void tenSequentialRetriesReturnSameOriginalMovementEvenAfterAnotherOperationChangesStock() throws Exception {
        String body = body(5, "RECEIPT", "10/10入荷");
        JsonNode first = response(adjust(1, body, "first", "admin").andExpect(status().isOk()).andReturn());
        for (int retry=0; retry<10; retry++)
            assertEquals(first, response(adjust(1, body, "first", "admin").andExpect(status().isOk()).andReturn()));
        assertEquals(15, stock(1)); assertEquals(1, movementCount());
        adjust(1, body(5, "CORRECTION", null), "another", "admin").andExpect(status().isOk());
        assertEquals(20, stock(1)); assertEquals(2, movementCount());
        clearInvocations(products);
        assertEquals(first, response(adjust(1, body, "first", "admin").andExpect(status().isOk()).andReturn()));
        verifyNoInteractions(products);
        assertEquals(20, stock(1)); assertEquals(2, movementCount());
        assertSafeMovement(first.toString());
        String history = mvc.perform(get(BASE + "1/inventory-movements").header("Authorization", tokens.get("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSafeMovement(history);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "   ", "　", "\n\t"})
    void nullBlankNotesAndJsonPropertyOrderReplayAsSameSemanticRequest(String note) throws Exception {
        var first = response(adjust(1, body(1, "RECEIPT", null), "note", "admin").andExpect(status().isOk()).andReturn());
        String reordered = "{ \"note\" : " + json.writeValueAsString(note) + ",\n \"reason\":\"RECEIPT\", \"delta\" : 1 }";
        assertEquals(first, response(adjust(1, reordered, "note", "admin").andExpect(status().isOk()).andReturn()));
        assertEquals(11, stock(1)); assertEquals(1, movementCount());
    }

    static Stream<Arguments> mismatches() {
        return Stream.of(Arguments.of(2L, 5, "RECEIPT", "入荷"), Arguments.of(1L, 6, "RECEIPT", "入荷"),
                Arguments.of(1L, 5, "CORRECTION", "入荷"), Arguments.of(1L, 5, "RECEIPT", "入荷B"),
                Arguments.of(1L, 5, "RECEIPT", " 入荷 "), Arguments.of(999L, 5, "RECEIPT", "入荷"));
    }
    @ParameterizedTest @MethodSource("mismatches")
    void successfulKeyCannotBeReusedForAnotherProductOrDeltaReasonOrNote(long id, int delta, String reason, String note) throws Exception {
        adjust(1, body(5, "RECEIPT", "入荷"), "same", "admin").andExpect(status().isOk());
        var before = snapshot(); clearInvocations(products);
        String path = BASE + id + "/inventory-adjustments";
        String error = adjust(id, body(delta, reason, note), "same", "admin")
                .andExpect(problem(409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict", path))
                .andExpect(jsonPath("$.detail").value("同じIdempotency-Keyに対する在庫調整内容が競合しています"))
                .andReturn().getResponse().getContentAsString();
        assertSafeError(error); verifyNoInteractions(products); assertEquals(before, snapshot());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", " a", "a ", "a/b", "日本語",
            "line\nbreak", "carriage\rreturn", "control\u0001", "tab\t"})
    void missingOrInvalidKeyHas400ContractBeforeActorLock(String key) throws Exception {
        var before = snapshot();
        String code = key != null && key.chars().anyMatch(c -> c == '\n' || c == '\r' || c == 1)
                ? "INVALID_REQUEST" : "INVALID_IDEMPOTENCY_KEY"; // Same firewall boundary as the Order API.
        String error = adjust(1, body(1, "RECEIPT", null), key, "admin")
                .andExpect(problem(400, code, code.equals("INVALID_REQUEST") ? "Invalid Request" : "Invalid Idempotency Key", PATH))
                .andReturn().getResponse().getContentAsString();
        assertSafeError(error); verify(users, never()).findByIdForUpdate(any()); verifyNoInteractions(products, movements);
        assertEquals(before, snapshot());
    }

    @Test void keyLengthBoundaryAndCaseSensitivityArePreserved() throws Exception {
        var before = snapshot();
        adjust(1, body(1, "RECEIPT", null), "a".repeat(129), "admin")
                .andExpect(problem(400, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency Key", PATH));
        assertEquals(before, snapshot());
        for (String key : List.of("a".repeat(128), "KeyABC", "keyabc"))
            adjust(1, body(1, "RECEIPT", null), key, "admin").andExpect(status().isOk());
        assertEquals(13, stock(1)); assertEquals(3, movementCount());
    }

    static Stream<Arguments> authorization() {
        return Stream.of("alice", "anonymous").flatMap(actor -> Stream.of(1L, 999L).flatMap(id ->
                Stream.of("missing", "bad-key", "bad-body").map(input -> Arguments.of(actor,id,input))));
    }
    @ParameterizedTest @MethodSource("authorization")
    void authorizationPrecedesHeaderBodyProductAndDoesNotConsumeKeys(String actor, long id, String input) throws Exception {
        var before = snapshot();
        String body = input.equals("bad-body") ? "{" : body(1, "RECEIPT", null);
        String key = input.equals("missing") ? null : input.equals("bad-key") ? "bad key" : "unauthorized";
        String path = BASE + id + "/inventory-adjustments";
        adjust(id, body, key, actor.equals("anonymous") ? null : actor)
                .andExpect(actor.equals("anonymous") ? problem(401, "UNAUTHENTICATED", "Unauthenticated", path)
                        : problem(403, "ACCESS_DENIED", "Access Denied", path));
        verify(users, never()).findByIdForUpdate(any()); verifyNoInteractions(products, movements);
        assertEquals(before, snapshot());
        adjust(1, body(1, "RECEIPT", null), "unauthorized", "admin").andExpect(status().isOk());
        assertEquals(1, movementCount());
    }

    @ParameterizedTest @CsvSource({"3,-5", "2147483647,1"})
    void underflowAndOverflowDoNotConsumeKeyAndCorrectedRequestCanSucceed(int initial, int delta) throws Exception {
        jdbc.update("UPDATE products SET stock=? WHERE id=1", initial);
        var before = snapshot();
        adjust(1, body(delta, "CORRECTION", null), "failed", "admin")
                .andExpect(problem(409, "INVENTORY_ADJUSTMENT_CONFLICT", "Inventory Adjustment Conflict", PATH));
        assertEquals(before, snapshot());
        adjust(1, body(-1, "CORRECTION", null), "failed", "admin").andExpect(status().isOk());
        assertEquals(initial-1, stock(1)); assertEquals(1, movementCount());
    }

    @Test void missingProductDoesNotConsumeKeyAndExistingProductCanUseItLater() throws Exception {
        var before = snapshot();
        adjust(999, body(1, "RECEIPT", null), "missing", "admin")
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", BASE + "999/inventory-adjustments"));
        assertEquals(before, snapshot());
        adjust(1, body(1, "RECEIPT", null), "missing", "admin").andExpect(status().isOk());
        assertEquals(11, stock(1)); assertEquals(1, movementCount());
    }

    @Test void beanValidationFailureDoesNotConsumeKey() throws Exception {
        var before = snapshot();
        adjust(1, body(0, "RECEIPT", null), "validation", "admin")
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", PATH));
        assertEquals(before, snapshot());
        adjust(1, body(1, "RECEIPT", null), "validation", "admin").andExpect(status().isOk());
        assertEquals(11, stock(1)); assertEquals(1, movementCount());
    }

    @Test void failureAfterActualMovementInsertRollsBackStockMovementAndKey() throws Exception {
        var before = snapshot();
        doAnswer(call -> {
            call.callRealMethod(); products.flush();
            throw new DataIntegrityViolationException("injected SQL password=private");
        }).when(movements).save(any(InventoryMovement.class));
        String error = adjust(1, body(5, "RECEIPT", null), "rollback", "admin")
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", PATH))
                .andReturn().getResponse().getContentAsString();
        assertSafeError(error); assertEquals(before, snapshot());
        reset(movements);
        adjust(1, body(5, "RECEIPT", null), "rollback", "admin").andExpect(status().isOk());
        assertEquals(15, stock(1)); assertEquals(1, movementCount());
    }

    @Test void realUniqueFallbackIs409AndDoesNotRequeryWithinFailedTransaction() throws Exception {
        adjust(1, body(5, "RECEIPT", null), "duplicate", "admin").andExpect(status().isOk());
        var before = snapshot(); clearInvocations(movements);
        doReturn(Optional.empty()).when(movements).findByActorIdAndIdempotencyKey(2L, "duplicate");
        String error = adjust(1, body(5, "RECEIPT", null), "duplicate", "admin")
                .andExpect(problem(409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict", PATH))
                .andReturn().getResponse().getContentAsString();
        assertSafeError(error); assertEquals(before, snapshot());
        verify(movements, times(1)).findByActorIdAndIdempotencyKey(2L, "duplicate");
    }

    @Test void unrelatedIntegrityViolationKeeps500AndDoesNotConsumeKey() throws Exception {
        var before = snapshot();
        doThrow(new DataIntegrityViolationException("SQL password=private", new SQLException(
                "Duplicate entry 'private' for key 'uk_users_username'", "23000", 1062))).when(products).flush();
        String error = adjust(1, body(5, "RECEIPT", null), "unrelated", "admin")
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", PATH))
                .andReturn().getResponse().getContentAsString();
        assertSafeError(error); assertEquals(before, snapshot());
        reset(products);
        adjust(1, body(5, "RECEIPT", null), "unrelated", "admin").andExpect(status().isOk());
        assertEquals(15, stock(1)); assertEquals(1, movementCount());
    }

    @RepeatedTest(3)
    void twentyConcurrentIdenticalRetriesAllReturnOneMovementAndApplyStockOnce() throws Exception {
        List<Callable<MvcResult>> requests = new ArrayList<>();
        for (int i=0; i<20; i++) requests.add(() -> adjust(1, body(5, "RECEIPT", "入荷"), "concurrent", "admin").andReturn());
        var results = concurrently(requests);
        JsonNode first = response(results.get(0));
        for (var result : results) {
            assertEquals(200, result.getResponse().getStatus());
            assertEquals(first, response(result));
        }
        assertSafeMovement(first.toString()); assertEquals(15, stock(1)); assertEquals(1, movementCount());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
    }

    @RepeatedTest(3)
    void concurrentMixedBodiesHaveOneWinnerGroupAndOneMovement() throws Exception {
        List<Callable<MvcResult>> requests = new ArrayList<>();
        for (int i=0; i<20; i++) {
            int delta = i%2==0 ? 5 : 6;
            requests.add(() -> adjust(1, body(delta, "RECEIPT", null), "mixed", "admin").andReturn());
        }
        var results = concurrently(requests);
        var winner = results.stream().filter(r -> r.getResponse().getStatus()==200).findFirst().orElseThrow();
        JsonNode expected = response(winner); int delta = expected.path("delta").asInt();
        assertTrue(delta==5 || delta==6);
        for (int i=0; i<results.size(); i++) {
            var result = results.get(i);
            if ((i%2==0 ? 5 : 6)==delta) {
                assertEquals(200, result.getResponse().getStatus()); assertEquals(expected, response(result));
            } else problem(409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict", PATH).match(result);
        }
        assertEquals(10+delta, stock(1)); assertEquals(1, movementCount());
    }

    @RepeatedTest(3)
    void concurrentDifferentProductsWithSameActorKeyCannotBothSucceed() throws Exception {
        var results = concurrently(List.of(
                () -> adjust(1, body(1, "RECEIPT", null), "cross-product", "admin").andReturn(),
                () -> adjust(2, body(1, "RECEIPT", null), "cross-product", "admin").andReturn()));
        assertEquals(1, results.stream().filter(r -> r.getResponse().getStatus()==200).count());
        var winner = response(results.stream().filter(r -> r.getResponse().getStatus()==200).findFirst().orElseThrow());
        long productId = winner.path("productId").asLong();
        for (int i=0; i<2; i++) if (i+1 != productId)
            problem(409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict", BASE + (i+1) + "/inventory-adjustments").match(results.get(i));
        assertEquals(productId==1 ? 11 : 10, stock(1)); assertEquals(productId==2 ? 6 : 5, stock(2));
        assertEquals(1, movementCount());
    }

    @Test void concurrentDifferentActorsCanUseSameKeyIndependently() throws Exception {
        var results = concurrently(List.of(
                () -> adjust(1, body(1, "RECEIPT", null), "actor-scoped", "admin").andReturn(),
                () -> adjust(1, body(1, "RECEIPT", null), "actor-scoped", "other").andReturn()));
        for (var result : results) assertEquals(200, result.getResponse().getStatus());
        assertNotEquals(response(results.get(0)).path("id"), response(results.get(1)).path("id"));
        assertEquals(List.of(2L,3L), jdbc.queryForList("SELECT actor_user_id FROM inventory_movements ORDER BY actor_user_id", Long.class));
        assertEquals(12, stock(1)); assertEquals(2, movementCount());
    }

    @Test void concurrentDifferentKeysAreDifferentOperations() throws Exception {
        var results = concurrently(List.of(
                () -> adjust(1, body(2, "RECEIPT", null), "key-A", "admin").andReturn(),
                () -> adjust(1, body(3, "RECEIPT", null), "key-B", "admin").andReturn()));
        for (var result : results) assertEquals(200, result.getResponse().getStatus());
        assertEquals(15, stock(1)); assertEquals(2, movementCount());
    }

    @Test void replayKeepsOneReferenceAndProductDeleteStillReturns409() throws Exception {
        adjust(1, body(1, "RECEIPT", null), "delete", "admin").andExpect(status().isOk());
        adjust(1, body(1, "RECEIPT", null), "delete", "admin").andExpect(status().isOk());
        var before = snapshot();
        mvc.perform(delete("/products/1").header("Authorization", tokens.get("admin")))
                .andExpect(problem(409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict", "/products/1"));
        assertEquals(1, movementCount()); assertEquals(before, snapshot());
    }

    @RepeatedTest(3)
    void cancellationWaitsForActorBeforeProductSoManualTransactionCanFinishWithoutLockCycle() throws Exception {
        long orderId = json.readTree(mvc.perform(post("/api/v1/me/orders").header("Authorization", tokens.get("alice"))
                .header("Idempotency-Key", "order").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":1,\"quantity\":2}]}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").longValue();
        var actorAttempt = new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("actor-lock-cancel")) actorAttempt.countDown();
            // Spring Data query methods are abstract; execute the same locked query after the test signal.
            return entities.createQuery("select u from User u where u.id = :id", User.class)
                    .setParameter("id", call.getArgument(0)).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
        }).when(users).findByIdForUpdate(2L);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "actor-lock-cancel"));
        List<Future<MvcResult>> result = new ArrayList<>();
        try {
            var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.executeWithoutResult(ignored -> {
                users.findByIdForUpdate(2L).orElseThrow();
                result.add(executor.submit(() -> mvc.perform(post("/api/v1/admin/orders/" + orderId + "/cancel")
                        .header("Authorization", tokens.get("admin"))).andReturn()));
                try { assertTrue(actorAttempt.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
                var request = new InventoryAdjustmentRequest(); request.setDelta(3); request.setReason(ManualInventoryReason.CORRECTION);
                inventory.adjust(1L, request, "while-cancel-waits");
            });
            assertEquals(200, result.get(0).get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(13, stock(1)); assertEquals(3, movementCount());
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE source_type='ORDER' "
                    + "AND idempotency_key IS NULL AND request_fingerprint IS NULL", Integer.class));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void openApiHasRequiredOpaqueHeaderErrorsAndNoResponseMetadata() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var post = document.path("paths").path(BASE + "{id}/inventory-adjustments").path("post");
        var header = header(post.path("parameters"), "Idempotency-Key");
        assertEquals("header", header.path("in").asText()); assertTrue(header.path("required").asBoolean());
        assertEquals("[A-Za-z0-9._:-]{1,128}", header.path("schema").path("pattern").asText());
        assertEquals(128, header.path("schema").path("maxLength").asInt());
        assertTrue(header.path("description").asText().contains("actor"));
        for (String status : List.of("400","401","403","404","409")) {
            var error = post.path("responses").path(status);
            assertEquals("#/components/schemas/ApiProblem", error.path("content").path("application/problem+json").path("schema").path("$ref").asText());
        }
        assertTrue(post.path("responses").path("400").toString().contains("INVALID_IDEMPOTENCY_KEY"));
        assertTrue(post.path("responses").path("409").toString().contains("IDEMPOTENCY_KEY_CONFLICT"));
        var movement = document.path("components").path("schemas").path("InventoryMovementResponse").path("properties");
        assertFalse(movement.has("idempotencyKey")); assertFalse(movement.has("requestFingerprint"));
        var get = document.path("paths").path(BASE + "{id}/inventory-movements").path("get");
        for (var parameter : get.path("parameters")) assertNotEquals("Idempotency-Key", parameter.path("name").asText());
    }

    private static JsonNode header(JsonNode parameters, String name) {
        for (var parameter : parameters) if (name.equals(parameter.path("name").asText())) return parameter;
        throw new AssertionError("header missing: " + name);
    }
    private ResultActions adjust(long id, String body, String key, String actor) throws Exception {
        var request = post(BASE + id + "/inventory-adjustments").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key!=null) request.header("Idempotency-Key", key);
        if (actor!=null) request.header("Authorization", tokens.get(actor));
        return mvc.perform(request);
    }
    private String body(int delta, String reason, String note) throws Exception {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("delta",delta); values.put("reason",reason); values.put("note",note);
        return json.writeValueAsString(values);
    }
    private JsonNode response(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private int stock(long id) { return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id); }
    private int movementCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class); }
    private Map<String,List<Map<String,Object>>> snapshot() {
        Map<String,List<Map<String,Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("products","orders","order_items","inventory_movements"))
            result.put(table,jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private void assertSafeMovement(String body) {
        for (String field : List.of("idempotencyKey","requestFingerprint","password","private-test-hash"))
            assertFalse(body.contains(field), field);
    }
    private void assertSafeError(String body) {
        assertSafeMovement(body);
        for (String field : List.of("SQLException","SQL","constraint","uk_","fk_","inventory_movements","private"))
            assertFalse(body.contains(field), field);
    }
    private List<MvcResult> concurrently(List<Callable<MvcResult>> actions) throws Exception {
        var ready = new CountDownLatch(actions.size()); var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(actions.size());
        try {
            var futures = actions.stream().map(action -> executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(30, TimeUnit.SECONDS)); return action.call();
            })).toList();
            assertTrue(ready.await(30, TimeUnit.SECONDS)); start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
