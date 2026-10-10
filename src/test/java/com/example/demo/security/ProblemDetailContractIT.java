package com.example.demo.security;

import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProblemDetailContractIT {
    private static final String ME = "/api/v1/me/orders", ADMIN = "/api/v1/admin/orders";
    private static final String PASSWORD = "private-contract-password";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String ISSUER = "inventory-management-system";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("problem_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET); registry.add("JWT_ISSUER", () -> ISSUER);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwt;
    @Autowired private UserRepository users;
    private String userToken, adminToken;
    private long productId;

    @BeforeEach
    void fixtures() throws Exception {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM order_items"); jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products"); jdbc.update("DELETE FROM users");
        register("alice").andExpect(status().isOk()); register("admin").andExpect(status().isOk());
        jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username='admin'");
        userToken = jwt.generateToken("alice"); adminToken = jwt.generateToken("admin");
        String body = mvc.perform(as(adminToken, post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":10}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        productId = json.readTree(body).path("id").asLong();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void validationHasNestedErrorsAndNoCredentialValues() throws Exception {
        mvc.perform(as(userToken, post(ME).header("Idempotency-Key", "problem-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":0,\"quantity\":0}]}")))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", ME))
                .andExpect(jsonPath("$.errors['items[0].productId']").value("商品IDは正の値を入力してください"))
                .andExpect(jsonPath("$.errors['items[0].quantity']").value("数量は1以上を入力してください"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":null,\"password\":\"private\"}"))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/auth/login"))
                .andExpect(jsonPath("$.errors.username").isString())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void malformedJsonAndUnsupportedMediaUseSafeContracts() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/auth/register"));
        mvc.perform(post("/auth/register").contentType(MediaType.TEXT_PLAIN).content(PASSWORD))
                .andExpect(problem(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", "/auth/register"));
    }

    @Test
    void missingAndInvalidIdempotencyKeysKeep400() throws Exception {
        for (String key : List.of("", "bad key")) mvc.perform(as(userToken, post(ME)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(orderBody(1))))
                .andExpect(problem(400, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency Key", ME));
        mvc.perform(as(userToken, post(ME).contentType(MediaType.APPLICATION_JSON).content(orderBody(1))))
                .andExpect(problem(400, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency Key", ME));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test
    void wrongPasswordAndAbsentUserHaveIdenticalProblemDetails() throws Exception {
        String wrong = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(problem(401, "INVALID_CREDENTIALS", "Invalid Credentials", "/auth/login"))
                .andReturn().getResponse().getContentAsString();
        String absent = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing\",\"password\":\"wrong-password\"}"))
                .andExpect(problem(401, "INVALID_CREDENTIALS", "Invalid Credentials", "/auth/login"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(wrong), json.readTree(absent));
        assertFalse(wrong.contains(PASSWORD)); assertFalse(wrong.contains(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "expired", "deleted-user"})
    void anonymousInvalidExpiredAndDeletedUserJwtShareSafe401(String scenario) throws Exception {
        String token = switch (scenario) {
            case "invalid" -> "malformed-private-token";
            case "expired" -> Jwts.builder().subject("alice").issuer(ISSUER)
                    .expiration(new Date(System.currentTimeMillis() - 60_000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
            case "deleted-user" -> {
                register("deleted-user").andExpect(status().isOk());
                String issued = jwt.generateToken("deleted-user");
                users.delete(users.findByUsername("deleted-user").orElseThrow());
                yield issued;
            }
            default -> null;
        };
        if (scenario.equals("deleted-user")) assertTrue(users.findByUsername("deleted-user").isEmpty());
        mvc.perform(as(token, get(ME).accept(MediaType.APPLICATION_JSON)))
                .andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", ME))
                .andExpect(jsonPath("$.detail").value("認証が必要です"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
    }

    @Test
    void filterAndMethodSecurityDenialsHaveSame403ExceptInstance() throws Exception {
        String filtered = mvc.perform(as(userToken, get(ADMIN)))
                .andExpect(problem(403, "ACCESS_DENIED", "Access Denied", ADMIN))
                .andReturn().getResponse().getContentAsString();
        String path = "/products/" + productId;
        String method = mvc.perform(as(userToken, put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":10,\"version\":0}")))
                .andExpect(problem(403, "ACCESS_DENIED", "Access Denied", path))
                .andReturn().getResponse().getContentAsString();
        ObjectNode first = (ObjectNode) json.readTree(filtered), second = (ObjectNode) json.readTree(method);
        first.remove("instance"); second.remove("instance"); assertEquals(first, second);
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
    }

    @Test
    void orderNotFoundKeeps404() throws Exception {
        mvc.perform(as(userToken, get(ME + "/999999")))
                .andExpect(problem(404, "ORDER_NOT_FOUND", "Order Not Found", ME + "/999999"));
    }

    @Test
    void idempotencyConflictDoesNotCreateAnotherOrderOrChangeInventory() throws Exception {
        create("problem-repeat", 1).andExpect(status().isOk());
        create("problem-repeat", 2).andExpect(problem(409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict", ME));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(9, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
    }

    @Test
    void repeatedCancellationKeeps409AndDoesNotRestoreTwice() throws Exception {
        long id = json.readTree(create("problem-cancel", 5).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        String path = ME + "/" + id + "/cancel";
        mvc.perform(as(userToken, post(path))).andExpect(status().isOk());
        mvc.perform(as(userToken, post(path)))
                .andExpect(problem(409, "ILLEGAL_ORDER_STATE_TRANSITION", "Illegal Order State Transition", path));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
    }

    @Test
    void duplicateUsernameKeeps409WithoutHashOrSql() throws Exception {
        String body = register("alice").andExpect(problem(409, "USERNAME_ALREADY_EXISTS", "Username Already Exists", "/auth/register"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(users.findByUsername("alice").orElseThrow().getPassword()));
        assertFalse(body.contains("uk_users_username")); assertFalse(body.contains(PASSWORD));
    }

    @Test
    void staleProductVersionIs409() throws Exception {
        String path = "/products/" + productId;
        mvc.perform(as(adminToken, put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":10,\"version\":999}")))
                .andExpect(problem(409, "CONCURRENT_UPDATE", "Concurrent Update", path));
    }

    @Test
    void salesRangeAndMalformedInstantKeep400CodesAndQueryIsOmittedFromInstance() throws Exception {
        String path = ADMIN + "/sales";
        mvc.perform(as(adminToken, get(path).param("from", "not-date")))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", path));
        mvc.perform(as(adminToken, get(path).param("from", "2026-10-10T00:00:00Z").param("to", "2026-10-09T00:00:00Z")))
                .andExpect(problem(400, "INVALID_ORDER_REQUEST", "Invalid Order Request", path));
    }

    @Test
    void firewallFailureBeforeDispatcherIs400ProblemDetail() throws Exception {
        mvc.perform(as(userToken, post(ME).header("Idempotency-Key", "bad\nkey")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(1))))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", ME));
    }

    @Test
    void existingProductNotFoundGenericErrorIsSafe500WithoutDomainRedesign() throws Exception {
        mvc.perform(as(userToken, get("/products/999999")))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/products/999999"))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"));
    }

    @Test
    void openApiUsesProblemSchemaAndMediaTypeForRequiredEndpoints() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var properties = document.path("components").path("schemas").path("ApiProblem").path("properties");
        for (String field : List.of("type", "title", "status", "detail", "instance", "code", "errors")) assertTrue(properties.has(field));
        for (String endpoint : List.of("/auth/register|post|409", "/auth/login|post|401", ME + "|post|400",
                ADMIN + "/sales|get|400", "/products/{id}|put|409")) {
            String[] parts = endpoint.split("\\|");
            var response = document.path("paths").path(parts[0]).path(parts[1]).path("responses").path(parts[2]);
            assertEquals("#/components/schemas/ApiProblem", response.path("content").path("application/problem+json").path("schema").path("$ref").asText());
            assertFalse(response.path("content").has("application/json"));
        }
        assertTrue(document.path("components").path("schemas").path("RegisterResponse").path("properties").has("username"));
    }

    private ResultActions register(String username) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", username, "password", PASSWORD))));
    }
    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }
    private String orderBody(int quantity) { return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}"; }
    private ResultActions create(String key, int quantity) throws Exception {
        return mvc.perform(as(userToken, post(ME).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(orderBody(quantity))));
    }
}
