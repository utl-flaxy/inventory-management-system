package com.example.demo.controller;

import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.JwtUtil;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
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
        "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ProductDeletionIT {
    private static final String PATH = "/products/1";
    private static final String DETAIL = "注文履歴または在庫履歴から参照されているため商品を削除できません";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("product_deletion");

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
    @MockitoSpyBean ProductRepository products;
    @MockitoSpyBean OrderItemRepository orderItems;
    @MockitoSpyBean InventoryMovementRepository movements;
    private String userToken, adminToken;

    @BeforeEach void fixtures() {
        SecurityContextHolder.clearContext();
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES (UUID_v4(),1,'alice','private-test-hash','ROLE_USER'),(UUID_v4(),2,'admin','private-test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES (1,'apple',100,10,0)");
        userToken = "Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, "alice");
        adminToken = "Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, "admin");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"order", "movement", "both"})
    void referencedProductsHaveIdenticalSafe409AndAllRowsRemainUnchanged(String reference) throws Exception {
        references(reference);
        var before = snapshot();

        assertSafeConflict(mvc.perform(delete(PATH).header("Authorization", adminToken)));

        verify(products, never()).delete(any(com.example.demo.entity.Product.class));
        verify(products, never()).flush();
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"order", "movement"})
    void actualFlushForeignKeyFallbackReturns409AndRollsBack(String reference) throws Exception {
        references(reference);
        var before = snapshot();
        // Product lock makes normal API writes serial. Bypass checks to exercise the final DB defense.
        doReturn(false).when(orderItems).existsByProductId(1L);
        doReturn(false).when(movements).existsByProductId(1L);

        assertSafeConflict(mvc.perform(delete(PATH).header("Authorization", adminToken)));

        verify(products).flush();
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @CsvSource({"order,fk_order_items_product", "movement,fk_inventory_movement_product"})
    void mariaDbReallyRejectsRawDeleteWith1451AndExactConstraint(String reference, String constraint) throws Exception {
        references(reference);
        var before = snapshot();

        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM products WHERE id=1"));

        var sql = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(1451, sql.getErrorCode());
        assertTrue(sql.getMessage().contains("CONSTRAINT " + (char) 96 + constraint + (char) 96 + " FOREIGN KEY"));
        assertEquals(before, snapshot());
    }

    static Stream<Arguments> unrelatedErrors() {
        return Stream.of(
                Arguments.of(new DataIntegrityViolationException("SQL password=private",
                        new SQLException(fk("unknown"), "23000", 1451))),
                Arguments.of(new DataIntegrityViolationException("SQL password=private",
                        new SQLException(fk("fk_inventory_movement_order"), "23000", 1451))),
                Arguments.of(new DataIntegrityViolationException("SQL password=private",
                        new SQLException(fk("fk_order_items_product"), "23000", 1452))),
                Arguments.of(new DataAccessResourceFailureException("connection password=private")),
                Arguments.of(new InvalidDataAccessResourceUsageException("syntax SQL password=private")));
    }

    @ParameterizedTest @MethodSource("unrelatedErrors")
    void unrelatedDatabaseFailuresStaySafe500AndRollbackDeletion(RuntimeException failure) throws Exception {
        var before = snapshot();
        doThrow(failure).when(products).flush();

        String body = mvc.perform(delete(PATH).header("Authorization", adminToken))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", PATH))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"))
                .andReturn().getResponse().getContentAsString();

        assertSafeBody(body);
        assertEquals(before, snapshot());
    }

    static Stream<Arguments> authorization() {
        return Stream.of("none", "order", "movement", "both", "missing").flatMap(reference ->
                Stream.of("user", "anonymous").map(actor -> Arguments.of(reference, actor)));
    }

    @ParameterizedTest @MethodSource("authorization")
    void authorizationPrecedesExistenceAndReferenceLookup(String reference, String actor) throws Exception {
        references(reference);
        clearInvocations(products, orderItems, movements);
        var before = snapshot();
        String path = reference.equals("missing") ? "/products/999" : PATH;
        var request = delete(path);
        if (actor.equals("user")) request.header("Authorization", userToken);

        mvc.perform(request).andExpect(actor.equals("user")
                ? problem(403, "ACCESS_DENIED", "Access Denied", path)
                : problem(401, "UNAUTHENTICATED", "Unauthenticated", path));

        verifyNoInteractions(products, orderItems, movements);
        assertEquals(before, snapshot());
    }

    @Test void missingProductIs404BeforeReferenceChecks() throws Exception {
        var before = snapshot();
        mvc.perform(delete("/products/999").header("Authorization", adminToken))
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/999"));
        verify(products).findByIdForUpdate(999L);
        verifyNoInteractions(orderItems, movements);
        assertEquals(before, snapshot());
    }

    @Test void unreferencedProductDeletionKeeps200WithEmptyBody() throws Exception {
        mvc.perform(delete(PATH).header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(content().string(""));
        assertCounts(0, 0, 0, 0);
    }

    @Test void openApiDelete409UsesOnlySharedProblemSchemaAndKeeps404() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var responses = document.path("paths").path("/products/{id}").path("delete").path("responses");
        var conflict = responses.path("409");
        assertTrue(conflict.path("description").asText().contains("PRODUCT_DELETE_CONFLICT"));
        assertEquals("#/components/schemas/ApiProblem", conflict.path("content").path("application/problem+json")
                .path("schema").path("$ref").asText());
        assertEquals(1, conflict.path("content").size());
        assertEquals("PRODUCT_NOT_FOUND", responses.path("404").path("description").asText());
        assertTrue(responses.has("401")); assertTrue(responses.has("403"));
    }

    @RepeatedTest(5)
    void deleteAndOrderCreationSerializeWithoutOrphansOr500() throws Exception {
        var result = concurrently(
                () -> mvc.perform(delete(PATH).header("Authorization", adminToken)).andReturn(),
                () -> order().andReturn());
        int deletion = result.get(0).getResponse().getStatus();
        int creation = result.get(1).getResponse().getStatus();

        if (creation == 200) {
            assertEquals(409, deletion);
            assertSafeConflictBody(result.get(0));
            assertCounts(1, 1, 1, 1);
            assertEquals(8, stock());
        } else {
            assertEquals(200, deletion);
            assertEquals(400, creation);
            assertEquals("INVALID_ORDER_REQUEST", code(result.get(1)));
            assertCounts(0, 0, 0, 0);
        }
        assertNoOrphans();
    }

    @RepeatedTest(5)
    void deleteAndManualAdjustmentSerializeWithoutLostStockOr500() throws Exception {
        var result = concurrently(
                () -> mvc.perform(delete(PATH).header("Authorization", adminToken)).andReturn(),
                () -> adjustment().andReturn());
        int deletion = result.get(0).getResponse().getStatus();
        int adjustment = result.get(1).getResponse().getStatus();

        if (adjustment == 200) {
            assertEquals(409, deletion);
            assertSafeConflictBody(result.get(0));
            assertCounts(1, 0, 0, 1);
            assertEquals(12, stock());
            assertEquals(10, jdbc.queryForObject("SELECT stock_before FROM inventory_movements", Integer.class));
            assertEquals(12, jdbc.queryForObject("SELECT stock_after FROM inventory_movements", Integer.class));
        } else {
            assertEquals(200, deletion);
            assertEquals(404, adjustment);
            assertEquals("PRODUCT_NOT_FOUND", code(result.get(1)));
            assertCounts(0, 0, 0, 0);
        }
        assertNoOrphans();
    }

    @RepeatedTest(3)
    void deleteAndCancelPreserveProductAndRestoreStockExactlyOnce() throws Exception {
        long id = json.readTree(order().andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("id").longValue();
        var result = concurrently(
                () -> mvc.perform(delete(PATH).header("Authorization", adminToken)).andReturn(),
                () -> mvc.perform(post("/api/v1/admin/orders/" + id + "/cancel")
                        .header("Authorization", adminToken)).andReturn());

        assertEquals(409, result.get(0).getResponse().getStatus());
        assertSafeConflictBody(result.get(0));
        assertEquals(200, result.get(1).getResponse().getStatus());
        assertCounts(1, 1, 1, 2);
        assertEquals(10, stock());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
        assertNoOrphans();
    }

    private void references(String reference) throws Exception {
        if (reference.equals("order")) {
            // Legacy order data has no audit movement; V5 does not fabricate historical records.
            jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,version) "
                    + "VALUES (1,1,200,'PENDING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)");
            jdbc.update("INSERT INTO order_items(id,order_id,product_id,quantity,price,product_name_snapshot) "
                    + "VALUES (1,1,1,2,100,'apple')");
            jdbc.update("UPDATE products SET stock=8 WHERE id=1");
        } else if (reference.equals("movement")) adjustment().andExpect(status().isOk());
        else if (reference.equals("both")) order().andExpect(status().isOk());
        clearInvocations(products, orderItems, movements);
    }

    private ResultActions order() throws Exception {
        return mvc.perform(post("/api/v1/me/orders").header("Authorization", userToken)
                .header("Idempotency-Key", "product-delete-race").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":1,\"quantity\":2}]}"));
    }

    private ResultActions adjustment() throws Exception {
        return mvc.perform(post("/api/v1/admin/products/1/inventory-adjustments").header("Authorization", adminToken)
                .header("Idempotency-Key", "product-delete-adjustment")
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":2,\"reason\":\"RECEIPT\"}"));
    }

    private void assertSafeConflict(ResultActions response) throws Exception {
        String body = response.andExpect(problem(409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict", PATH))
                .andExpect(jsonPath("$.detail").value(DETAIL)).andReturn().getResponse().getContentAsString();
        assertSafeBody(body);
    }

    private void assertSafeConflictBody(MvcResult result) throws Exception {
        problem(409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict", PATH).match(result);
        jsonPath("$.detail").value(DETAIL).match(result);
        assertSafeBody(result.getResponse().getContentAsString());
    }

    private void assertSafeBody(String body) {
        for (String value : List.of("SQLException", "constraint", "fk_", "order_items", "inventory_movements",
                "SQL", "password", "private-test-hash", "private"))
            assertFalse(body.contains(value), value);
    }

    private String code(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : List.of("products", "orders", "order_items", "inventory_movements"))
            state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return state;
    }

    private int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class); }

    private void assertCounts(int productCount, int orderCount, int itemCount, int movementCount) {
        var expected = Map.of("products", productCount, "orders", orderCount, "order_items", itemCount,
                "inventory_movements", movementCount);
        expected.forEach((table, count) -> assertEquals(count,
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table));
    }

    private void assertNoOrphans() {
        for (String table : List.of("order_items", "inventory_movements"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                    + " r LEFT JOIN products p ON p.id=r.product_id WHERE p.id IS NULL", Integer.class));
    }

    private List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race start timeout");
                return action.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(60, TimeUnit.SECONDS), futures.get(1).get(60, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static String fk(String constraint) {
        return "Cannot delete or update a parent row: a foreign key constraint fails (db.table, CONSTRAINT "
                + (char) 96 + constraint + (char) 96 + " FOREIGN KEY (product_id) REFERENCES products (id))";
    }
}
