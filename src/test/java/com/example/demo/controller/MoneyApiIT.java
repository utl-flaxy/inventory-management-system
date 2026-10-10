package com.example.demo.controller;

import com.example.demo.dto.OrderItemRequest;
import com.example.demo.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MoneyApiIT {
    private static final String SECRET = Base64.getEncoder()
            .encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String ME = "/api/v1/me/orders";
    private static final String ADMIN = "/api/v1/admin/orders";
    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("money_api");

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

    @BeforeEach
    void fixtures() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER'),(2,'admin','test-hash','ROLE_ADMIN')");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (String table : List.of("order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2147483647L, 2147483648L, 3000000000L, Long.MAX_VALUE})
    void productCreateAndUpdateAcceptLongPricesAndPreserveVersionConflict(long price) throws Exception {
        long id = product(price, 10);
        assertEquals(price, jdbc.queryForObject("SELECT price FROM products WHERE id=?", Long.class, id));
        var body = Map.of("name", "updated", "price", price, "stock", 10, "version", 0L);
        mvc.perform(put("/products/" + id).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(price))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.stock").value(10));
        var before = snapshot();
        mvc.perform(put("/products/" + id).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "9223372036854775808"})
    void invalidProductPriceIs400ForCreateAndUpdateWithoutDatabaseChanges(String price) throws Exception {
        long id = product(1L, 10);
        var before = snapshot();
        String body = "{\"name\":\"invalid\",\"price\":" + price + ",\"stock\":10,\"version\":0}";
        mvc.perform(post("/products").header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/products/" + id).header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertEquals(before, snapshot());
    }

    @Test
    void highValueOrderPersistsSnapshotsReturnsNumbersAndExportsFullAmounts() throws Exception {
        long productId = product(3_000_000_000L, 10);
        String response = createOrder(new OrderItemRequest(productId, 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrice").value(6_000_000_000L))
                .andExpect(jsonPath("$.items[0].unitPrice").value(3_000_000_000L))
                .andExpect(jsonPath("$.items[0].lineTotal").value(6_000_000_000L))
                .andReturn().getResponse().getContentAsString();
        long orderId = json.readTree(response).get("id").longValue();
        assertTrue(json.readTree(response).get("totalPrice").isIntegralNumber());
        assertEquals(8, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
        assertEquals(3_000_000_000L, jdbc.queryForObject("SELECT price FROM order_items WHERE order_id=?", Long.class, orderId));
        assertEquals(6_000_000_000L, jdbc.queryForObject("SELECT total_price FROM orders WHERE id=?", Long.class, orderId));
        String currentProduct = mvc.perform(get("/products/" + productId).header("Authorization", adminToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long version = json.readTree(currentProduct).get("version").longValue();
        mvc.perform(put("/products/" + productId).header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "changed", "price", 4_000_000_000L,
                                "stock", 8, "version", version))))
                .andExpect(status().isOk());
        mvc.perform(get(ME + "/" + orderId).header("Authorization", "Bearer " + jwt.generateToken("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPrice").value(6_000_000_000L))
                .andExpect(jsonPath("$.items[0].productName").value("expensive-item"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(3_000_000_000L))
                .andExpect(jsonPath("$.items[0].lineTotal").value(6_000_000_000L));
        mvc.perform(get(ADMIN + "/sales").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(0))
                .andExpect(jsonPath("$.totalOrders").value(0));
        String csv = mvc.perform(get(ADMIN + "/export").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(csv.contains(orderId + ",expensive-item,2,3000000000,6000000000,PENDING"));
        complete(orderId);
        mvc.perform(get(ADMIN + "/sales").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(6_000_000_000L))
                .andExpect(jsonPath("$.totalOrders").value(1));
    }

    @Test
    void multipleHighValueLinesPersistEighteenBillionYen() throws Exception {
        long first = product(3_000_000_000L, 10), second = product(4_000_000_000L, 10);
        createOrder(new OrderItemRequest(first, 2), new OrderItemRequest(second, 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPrice").value(18_000_000_000L))
                .andExpect(jsonPath("$.items[0].lineTotal").value(6_000_000_000L))
                .andExpect(jsonPath("$.items[1].lineTotal").value(12_000_000_000L));
        assertEquals(18_000_000_000L, jdbc.queryForObject("SELECT total_price FROM orders", Long.class));
        assertEquals(List.of(8, 7), jdbc.queryForList("SELECT stock FROM products ORDER BY id", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {4611686018427387904L, Long.MAX_VALUE})
    void longMultiplicationOverflowIs400AndLeavesEveryRecordUnchanged(long price) throws Exception {
        long productId = product(price, 10);
        var before = snapshot();
        createOrder(new OrderItemRequest(productId, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
        assertEquals(before, snapshot());
    }

    @Test
    void longSumOverflowIs400AndDoesNotReduceEitherStockOrInsertAnyOrder() throws Exception {
        long first = product(Long.MAX_VALUE, 10), second = product(1L, 10);
        var before = snapshot();
        createOrder(new OrderItemRequest(first, 1), new OrderItemRequest(second, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
        assertEquals(before, snapshot());
    }

    @Test
    void longMaximumOrderAndSalesAreRepresentableWithQuantityOne() throws Exception {
        long productId = product(Long.MAX_VALUE, 1);
        String response = createOrder(new OrderItemRequest(productId, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrice").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.items[0].unitPrice").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.items[0].lineTotal").value(Long.MAX_VALUE))
                .andReturn().getResponse().getContentAsString();
        complete(json.readTree(response).get("id").longValue());
        mvc.perform(get(ADMIN + "/sales").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSales").value(Long.MAX_VALUE));
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
    }

    @Test
    void openApiUsesInt64MoneyAndKeepsExistingNamesAndInt32Quantities() throws Exception {
        String response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode schemas = json.readTree(response).path("components").path("schemas");
        for (var field : Map.of("ProductRequest", List.of("price"), "ProductUpdateRequest", List.of("price"),
                "ProductResponse", List.of("price"), "OrderResponse", List.of("totalPrice"),
                "OrderItemResponse", List.of("unitPrice", "lineTotal"), "SalesResponse", List.of("totalSales")).entrySet()) {
            for (String name : field.getValue()) {
                JsonNode property = schemas.path(field.getKey()).path("properties").path(name);
                assertEquals("integer", property.path("type").asText(), field.getKey() + "." + name);
                assertEquals("int64", property.path("format").asText(), field.getKey() + "." + name);
            }
        }
        assertEquals("int32", schemas.path("ProductRequest").path("properties").path("stock").path("format").asText());
        assertEquals("int32", schemas.path("OrderItemResponse").path("properties").path("quantity").path("format").asText());
    }

    private long product(long price, int stock) throws Exception {
        String response = mvc.perform(post("/products").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "expensive-item", "price", price, "stock", stock))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(price))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        assertTrue(body.get("price").isIntegralNumber());
        return body.get("id").longValue();
    }

    private ResultActions createOrder(OrderItemRequest... items) throws Exception {
        return mvc.perform(post(ME).header("Idempotency-Key", com.example.demo.TestOrders.key()).header("Authorization", "Bearer " + jwt.generateToken("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(com.example.demo.TestOrders.request(items))));
    }

    private String adminToken() {
        return "Bearer " + jwt.generateToken("admin");
    }

    private void complete(long orderId) throws Exception {
        mvc.perform(post(ADMIN + "/" + orderId + "/ship").header("Authorization", adminToken()))
                .andExpect(status().isOk());
        mvc.perform(post(ADMIN + "/" + orderId + "/complete").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isString());
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("products", jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                "orders", jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
                "items", jdbc.queryForList("SELECT * FROM order_items ORDER BY id"),
                "users", jdbc.queryForList("SELECT * FROM users ORDER BY id"));
    }
}
