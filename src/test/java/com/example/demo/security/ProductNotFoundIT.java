package com.example.demo.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductNotFoundIT {
    private static final long MISSING_ID = 999999;
    private static final String ORDERS = "/api/v1/me/orders";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("product_not_found");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("JWT_ISSUER", () -> "inventory-management-system");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwt;
    private String userToken, adminToken;
    private long productId;

    @BeforeEach
    void fixtures() throws Exception {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        for (String username : List.of("alice", "admin")) {
            mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("username", username, "password", "test-password"))))
                    .andExpect(status().isOk());
        }
        jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username='admin'");
        userToken = jwt.generateToken("alice");
        adminToken = jwt.generateToken("admin");
        productId = json.readTree(mvc.perform(as(adminToken, post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":10}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingGetPutDeleteHaveIdentical404BodiesAndNoDatabaseChanges() throws Exception {
        var before = snapshot();
        JsonNode expected = null;
        for (String method : List.of("GET", "PUT", "DELETE")) {
            String path = "/products/" + MISSING_ID;
            JsonNode body = json.readTree(mvc.perform(as(adminToken, resource(method, Long.toString(MISSING_ID), update(0L))))
                    .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", path))
                    .andExpect(jsonPath("$.detail").value("商品が存在しません"))
                    .andReturn().getResponse().getContentAsString());
            if (expected == null) expected = body;
            else assertEquals(expected, body);
            assertEquals(before, snapshot());
        }
    }

    @Test
    void authenticatedUserCanGetMissingProductAs404() throws Exception {
        mvc.perform(as(userToken, get("/products/" + MISSING_ID)))
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/" + MISSING_ID));
    }

    @Test
    void missingProductWithNonmatchingButValidVersionIs404Not409() throws Exception {
        var before = snapshot();
        mvc.perform(as(adminToken, resource("PUT", Long.toString(MISSING_ID), update(123L))))
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/" + MISSING_ID));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @CsvSource({"PUT,true", "PUT,false", "DELETE,true", "DELETE,false"})
    void userIsDeniedRegardlessOfProductExistence(String method, boolean exists) throws Exception {
        String id = Long.toString(exists ? productId : MISSING_ID);
        var before = snapshot();
        mvc.perform(as(userToken, resource(method, id, update(0L))))
                .andExpect(problem(403, "ACCESS_DENIED", "Access Denied", "/products/" + id));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void anonymousResourceRequestsRemain401(String method) throws Exception {
        var before = snapshot();
        mvc.perform(resource(method, Long.toString(MISSING_ID), update(0L)))
                .andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", "/products/" + MISSING_ID));
        assertEquals(before, snapshot());
    }

    @Test
    void existingProductDetailUpdateAndDeleteKeep200() throws Exception {
        String id = Long.toString(productId);
        mvc.perform(as(userToken, resource("GET", id, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("apple"));
        mvc.perform(as(adminToken, resource("PUT", id, update(0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("orange"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(as(adminToken, resource("DELETE", id, null))).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class));
        mvc.perform(as(adminToken, resource("DELETE", id, null)))
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/" + id));
    }

    @Test
    void staleVersionKeeps409AndDoesNotUpdateProduct() throws Exception {
        var before = snapshot();
        mvc.perform(as(adminToken, resource("PUT", Long.toString(productId), update(123L))))
                .andExpect(problem(409, "CONCURRENT_UPDATE", "Concurrent Update", "/products/" + productId));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1})
    void invalidVersionIsValidatedBeforeMissingProductLookup(Long version) throws Exception {
        var before = snapshot();
        mvc.perform(as(adminToken, resource("PUT", Long.toString(MISSING_ID), update(version))))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/products/" + MISSING_ID))
                .andExpect(jsonPath("$.errors.version").isString());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "price", "stock"})
    void existingProductFieldValidationStillReturns400(String field) throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(update(0L));
        switch (field) {
            case "name" -> body.put("name", "");
            case "price" -> body.put("price", 0);
            default -> body.put("stock", -1);
        }
        var before = snapshot();
        var result = mvc.perform(as(adminToken, resource("PUT", Long.toString(productId), json.writeValueAsString(body))));
        if (field.equals("stock")) {
            result.andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/" + productId));
        } else {
            result.andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/products/" + productId))
                    .andExpect(jsonPath("$.errors." + field).isString());
        }
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void malformedProductIdKeeps400(String method) throws Exception {
        var before = snapshot();
        mvc.perform(as(adminToken, resource(method, "not-number", update(0L))))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/not-number"));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void zeroAndNegativeIdsUseExistingLookupRoutingAndReturn404(long id) throws Exception {
        var before = snapshot();
        for (String method : List.of("GET", "PUT", "DELETE")) {
            mvc.perform(as(adminToken, resource(method, Long.toString(id), update(0L))))
                    .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/" + id));
        }
        assertEquals(before, snapshot());
    }

    @Test
    void emptyListAndSearchRemainSuccessfulPages() throws Exception {
        jdbc.update("DELETE FROM products");
        for (String path : List.of("/products", "/products/search")) {
            mvc.perform(as(userToken, get(path).param("keyword", "missing")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test
    void missingOrderProductKeeps400AndDoesNotConsumeIdempotencyKey() throws Exception {
        var before = snapshot();
        mvc.perform(as(userToken, post(ORDERS).header("Idempotency-Key", "missing-product-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(order(MISSING_ID))))
                .andExpect(problem(400, "INVALID_ORDER_REQUEST", "Invalid Order Request", ORDERS));
        assertEquals(before, snapshot());
        mvc.perform(as(userToken, post(ORDERS).header("Idempotency-Key", "missing-product-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(order(productId))))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(9, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
    }

    @Test
    void actualForeignKeyDeleteFailureIsSafe500AndNeverNotFound() throws Exception {
        mvc.perform(as(userToken, post(ORDERS).header("Idempotency-Key", "linked-product")
                        .contentType(MediaType.APPLICATION_JSON).content(order(productId))))
                .andExpect(status().isOk());
        var before = snapshot();
        String response = mvc.perform(as(adminToken, resource("DELETE", Long.toString(productId), null)))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/products/" + productId))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("foreign key"));
        assertFalse(response.contains("order_items"));
        assertEquals(before, snapshot());
    }

    @Test
    void openApiDocumentsOnlyResource404sWithSharedProblemSchema() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String method : List.of("get", "put", "delete")) {
            var response = document.path("paths").path("/products/{id}").path(method).path("responses").path("404");
            assertEquals("PRODUCT_NOT_FOUND", response.path("description").asText());
            assertEquals("#/components/schemas/ApiProblem", response.path("content")
                    .path("application/problem+json").path("schema").path("$ref").asText());
            assertEquals(1, response.path("content").size());
        }
        assertFalse(document.path("paths").path("/products").path("get").path("responses").has("404"));
        assertFalse(document.path("paths").path("/products").path("post").path("responses").has("404"));
        assertFalse(document.path("paths").path("/products/search").path("get").path("responses").has("404"));
    }

    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
                jdbc.queryForList("SELECT * FROM order_items ORDER BY id"));
    }

    private MockHttpServletRequestBuilder resource(String method, String id, String body) {
        String path = "/products/" + id;
        return switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path).contentType(MediaType.APPLICATION_JSON).content(body);
            default -> delete(path);
        };
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String update(Long version) {
        return "{\"name\":\"orange\",\"price\":200,\"version\":" + version + "}";
    }

    private String order(long id) {
        return "{\"items\":[{\"productId\":" + id + ",\"quantity\":1}]}";
    }
}
