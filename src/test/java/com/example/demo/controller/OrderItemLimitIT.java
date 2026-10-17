package com.example.demo.controller;

import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderRequest;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.CurrentUser;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderRequestFingerprint;
import com.example.demo.service.OrderService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderItemLimitIT {
    private static final String PATH = "/api/v1/me/orders";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final List<String> TABLES = List.of("inventory_movements", "order_items", "orders", "products", "users");
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("order_item_limit");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    @MockitoSpyBean OrderService service;
    @MockitoSpyBean CurrentUser currentUser;
    @MockitoSpyBean ProductRepository products;
    @MockitoSpyBean OrderRepository orders;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','test-hash','ROLE_USER')");
        var rows = new ArrayList<Object[]>();
        for (long id = 1; id <= 101; id++) rows.add(new Object[]{id, "limit-product-" + id, id * 10, 3});
        jdbc.batchUpdate("INSERT INTO products(id,name,price,stock,version) VALUES(?,?,?,?,0)", rows);
        clearInvocations(service, currentUser, products, orders);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void hundredItemsKeepSnapshotsTotalsStockLedgerAndIdempotentRetryInEitherOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        var reversed = items(100); Collections.reverse(reversed);
        JsonNode first = json.readTree(create(key, request(reversed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrice").value(50_500))
                .andExpect(jsonPath("$.items.length()").value(100))
                .andReturn().getResponse().getContentAsString());
        long id = first.path("id").asLong();
        assertEquals(1, count("orders")); assertEquals(100, count("order_items")); assertEquals(100, count("inventory_movements"));
        assertEquals(100, jdbc.queryForObject("SELECT SUM(quantity) FROM order_items", Integer.class));
        assertEquals(50_500L, jdbc.queryForObject("SELECT SUM(price * quantity) FROM order_items", Long.class));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id<=100 AND stock=2 AND version=1", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT stock FROM products WHERE id=101", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id=101", Long.class));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE delta=-1 AND stock_before=3 AND stock_after=2 AND reason='ORDER_CREATED' AND source_type='ORDER' AND source_id=? AND actor_user_id=1", Integer.class, id));
        var quantities = new TreeMap<Long, Integer>();
        for (int index = 0; index < 100; index++) {
            long productId = index + 1;
            var item = first.path("items").get(index);
            assertEquals(productId, item.path("productId").asLong());
            assertEquals("limit-product-" + productId, item.path("productName").asText());
            assertEquals(productId * 10, item.path("unitPrice").asLong());
            assertEquals(productId * 10, item.path("lineTotal").asLong());
            quantities.put(productId, 1);
        }
        assertTrue(OrderRequestFingerprint.fromValidatedItems(quantities).equals(jdbc.queryForObject(
                "SELECT request_fingerprint FROM orders WHERE id=?", String.class, id)));
        var beforeRetry = snapshot();
        JsonNode retry = json.readTree(create(key, request(items(100))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(normalized(first).equals(normalized(retry)), "Retry changed an order response field");
        assertUnchanged(beforeRetry);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void outsideBoundsIsValidation400WithoutDbChangeOrIdempotencyReservation(int count) throws Exception {
        String key = UUID.randomUUID().toString();
        var before = snapshot();
        var attempt = create(key, request(items(count)));
        System.out.printf("AUD08 input-items=%d status=%d orders=%d order-items=%d movements=%d%n",
                count, attempt.andReturn().getResponse().getStatus(), count("orders"), count("order_items"), count("inventory_movements"));
        attempt
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", PATH))
                .andExpect(jsonPath("$.errors.items").value(count == 0
                        ? "注文明細は1件以上必要です" : "注文明細は100件以内で入力してください"));
        verifyNoInteractions(service, currentUser, products, orders);
        assertUnchanged(before);
        create(key, request(items(1))).andExpect(status().isOk());
        assertEquals(1, count("orders"));
    }

    @Test
    void oversizedRetryCannotChangeAnExistingOrderAndValidRetryStillWorks() throws Exception {
        String key = UUID.randomUUID().toString();
        long id = json.readTree(create(key, request(items(1))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        var before = snapshot(); clearInvocations(service, currentUser, products, orders);
        create(key, request(items(101))).andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", PATH))
                .andExpect(jsonPath("$.errors.items").value("注文明細は100件以内で入力してください"));
        verifyNoInteractions(service, currentUser, products, orders);
        assertUnchanged(before);
        create(key, request(items(1))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertUnchanged(before);
    }

    @Test
    void duplicateAtHundredItemsKeepsBusiness400BeforeAnyProductLockOrWrite() throws Exception {
        var entries = items(100); entries.set(99, new OrderItemRequest(1L, 1));
        var before = snapshot();
        create(UUID.randomUUID().toString(), request(entries))
                .andExpect(problem(400, "INVALID_ORDER_REQUEST", "Invalid Order Request", PATH))
                .andExpect(jsonPath("$.detail").value("同じ商品を複数の明細に指定できません"));
        verifyNoInteractions(currentUser, products, orders);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void invalidQuantityOnLastOfHundredItemsRemainsValidation400AndDatabaseUnchanged(Integer quantity) throws Exception {
        var entries = items(100); entries.set(99, new OrderItemRequest(100L, quantity));
        var before = snapshot();
        create(UUID.randomUUID().toString(), request(entries))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", PATH))
                .andExpect(jsonPath("$.errors['items[99].quantity']").isString());
        verifyNoInteractions(service, currentUser, products, orders);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transactionalServiceEntriesRejectOversizeBeforeBusinessReadsAndWrites(boolean responseEntry) {
        com.example.demo.TestOrders.authenticate();
        var before = snapshot();
        var request = request(items(101));
        var error = assertThrows(InvalidOrderRequestException.class, () -> {
            if (responseEntry) service.createOrderResponse(request, "service-boundary");
            else service.createOrder(request, "service-boundary");
        });
        assertEquals("注文明細は100件以内で入力してください", error.getMessage());
        verifyNoInteractions(currentUser, products, orders);
        assertUnchanged(before);
    }

    @Test
    void openApiLimitsRequestArrayWithoutImposingCeilingOnStoredOrderResponses() throws Exception {
        var api = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var schemas = api.path("components").path("schemas");
        var entries = schemas.path("OrderRequest").path("properties").path("items");
        assertEquals(1, entries.path("minItems").asInt(), () -> "OrderRequest.items schema: " + entries);
        assertEquals(100, entries.path("maxItems").asInt());
        assertTrue(entries.path("description").asText().contains("100"));
        assertEquals("#/components/schemas/OrderItemRequest", entries.path("items").path("$ref").asText());
        assertFalse(schemas.path("OrderResponse").path("properties").path("items").has("maxItems"));
        var operation = api.path("paths").path(PATH).path("post");
        assertEquals("#/components/schemas/OrderRequest", operation.path("requestBody").path("content")
                .path("application/json").path("schema").path("$ref").asText());
        assertEquals("#/components/schemas/ApiProblem", operation.path("responses").path("400")
                .path("content").path("application/problem+json").path("schema").path("$ref").asText());
    }

    private ArrayList<OrderItemRequest> items(int count) {
        var entries = new ArrayList<OrderItemRequest>();
        for (long id = 1; id <= count; id++) entries.add(new OrderItemRequest(id, 1));
        return entries;
    }

    private OrderRequest request(List<OrderItemRequest> entries) {
        var request = new OrderRequest(); request.setItems(entries); return request;
    }

    private ResultActions create(String key, OrderRequest request) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + jwt.generateToken("alice"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var before = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) before.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return before;
    }

    private void assertUnchanged(Map<String, List<Map<String, Object>>> before) {
        assertTrue(before.equals(snapshot()), "Database rows changed after rejected or replayed request");
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private JsonNode normalized(JsonNode response) {
        ObjectNode copy = (ObjectNode) response.deepCopy();
        for (String field : List.of("orderedAt", "updatedAt"))
            copy.put(field, Instant.parse(copy.path(field).asText()).truncatedTo(ChronoUnit.MICROS).toString());
        return copy;
    }
}
