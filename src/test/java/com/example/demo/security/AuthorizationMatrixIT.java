package com.example.demo.security;

import com.example.demo.controller.AdminOrderController;
import com.example.demo.repository.OrderRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorizationMatrixIT {
    @Test
    void deploymentHealthEndpointIsPublicAndServedAfterDatabaseStartup() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths").isMap());
    }

    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String ISSUER = "inventory-management-system";
    private static final String ME = "/api/v1/me/orders";
    private static final String ADMIN = "/api/v1/admin/orders";

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("order_authorization");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("JWT_ISSUER", () -> ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AdminOrderController adminController;
    @Autowired
    private CustomUserDetailsService userDetailsService;
    @Autowired
    private JwtUtil jwt;
    @Autowired
    private ObjectMapper json;

    enum Actor {
        ANONYMOUS(null, 0, 101), USER_A("alice", 1, 101), USER_B("bob", 2, 102), ADMIN("admin", 3, 103);
        final String username;
        final long customerId;
        final long ownOrderId;

        Actor(String username, long customerId, long ownOrderId) {
            this.username = username;
            this.customerId = customerId;
            this.ownOrderId = ownOrderId;
        }
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO users (auth_uid,id, username, password, role) VALUES (UUID_v4(),1,'alice','test-hash','ROLE_USER'), (UUID_v4(),2,'bob','test-hash','ROLE_USER'), (UUID_v4(),3,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id, name, price, stock) VALUES (1,'apple',100,94)");
        jdbc.update("INSERT INTO orders (id, customer_id, total_price, status, ordered_at, updated_at) VALUES (101,1,200,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP), (102,2,300,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP), (103,3,100,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price, product_name_snapshot) VALUES (101,101,1,2,100,'apple'), (102,102,1,3,100,'apple'), (103,103,1,1,100,'apple')");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
    }

    private MockHttpServletRequestBuilder as(Actor actor, MockHttpServletRequestBuilder request) {
        return actor == Actor.ANONYMOUS ? request
                : request.header("Authorization", "Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, actor.username));
    }

    private record Snapshot(List<Map<String, Object>> products, List<Map<String, Object>> orders,
                            List<Map<String, Object>> items) { }

    private Snapshot snapshot() {
        return new Snapshot(jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
                jdbc.queryForList("SELECT * FROM order_items ORDER BY id"));
    }

    static Stream<Arguments> selfMatrix() {
        return Stream.of(Actor.values()).flatMap(actor -> Stream.of("create", "list", "detail-own", "detail-other", "cancel-own", "cancel-other")
                .map(endpoint -> Arguments.of(actor, endpoint)));
    }

    @ParameterizedTest(name = "self {0} {1}")
    @MethodSource("selfMatrix")
    void selfEndpointsOnlyExposeAndCancelTheCurrentCustomersOrders(Actor actor, String endpoint) throws Exception {
        long id = endpoint.endsWith("other") ? (actor == Actor.USER_B ? 101 : 102) : actor.ownOrderId;
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "create" -> post(ME).header("Idempotency-Key", com.example.demo.TestOrders.key()).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":1,\"quantity\":1}]}");
            case "list" -> get(ME);
            case "detail-own", "detail-other" -> get(ME + "/" + id);
            default -> post(ME + "/" + id + "/cancel");
        };
        int expected = actor == Actor.ANONYMOUS ? 401 : endpoint.endsWith("other") ? 404 : 200;
        Snapshot before = snapshot();
        var result = mvc.perform(as(actor, request)).andExpect(status().is(expected));
        if (expected == 401) result.andExpect(com.example.demo.TestApiProblems.problem(401,
                "UNAUTHENTICATED", "Unauthenticated", request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()));
        if (expected == 404) {
            result.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        } else if (expected == 200 && endpoint.equals("list")) {
            result.andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(actor.ownOrderId))
                    .andExpect(jsonPath("$.content[0].customer.id").value(actor.customerId))
                    .andExpect(jsonPath("$.content[0].customer.password").doesNotExist())
                    .andExpect(jsonPath("$.content[0].customer.role").doesNotExist());
        } else if (expected == 200) {
            result.andExpect(jsonPath("$.customer.id").value(actor.customerId))
                    .andExpect(jsonPath("$.customer.username").value(actor.username))
                    .andExpect(jsonPath("$.customer.password").doesNotExist())
                    .andExpect(jsonPath("$.customer.role").doesNotExist())
                    .andExpect(jsonPath("$.items[0].product").doesNotExist());
        }
        if (expected != 200 || (!endpoint.equals("create") && !endpoint.startsWith("cancel"))) {
            assertEquals(before, snapshot(), "拒否/参照リクエストではDBを変更しない");
        } else if (endpoint.equals("create")) {
            assertEquals(93, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
            assertEquals(actor.customerId, jdbc.queryForObject("SELECT customer_id FROM orders ORDER BY id DESC LIMIT 1", Long.class));
        } else {
            int quantity = actor == Actor.USER_A ? 2 : actor == Actor.USER_B ? 3 : 1;
            assertEquals(94 + quantity, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
        }
    }

    static Stream<Arguments> adminMatrix() {
        return Stream.of(Actor.values()).flatMap(actor -> Stream.of("list", "detail", "ship", "complete", "cancel", "status", "sales", "export")
                .map(endpoint -> Arguments.of(actor, endpoint)));
    }

    private MockHttpServletRequestBuilder adminRequest(String endpoint, long id) {
        return switch (endpoint) {
            case "list" -> get(ADMIN);
            case "detail" -> get(ADMIN + "/" + id);
            case "sales", "export" -> get(ADMIN + "/" + endpoint);
            case "status" -> patch(ADMIN + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}");
            default -> post(ADMIN + "/" + id + "/" + endpoint);
        };
    }

    @ParameterizedTest(name = "admin {0} {1}")
    @MethodSource("adminMatrix")
    void adminEndpointsRejectUsersBeforeAnyOrderReadOrMutation(Actor actor, String endpoint) throws Exception {
        if (endpoint.equals("complete")) {
            jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id=101");
        }
        Snapshot before = snapshot();
        int expected = actor == Actor.ANONYMOUS ? 401 : actor == Actor.ADMIN ? 200 : 403;
        var result = mvc.perform(as(actor, adminRequest(endpoint, 101))).andExpect(status().is(expected));
        if (expected == 401 || expected == 403) result.andExpect(com.example.demo.TestApiProblems.problem(expected,
                expected == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED",
                expected == 401 ? "Unauthenticated" : "Access Denied",
                adminRequest(endpoint, 101).buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()));
        if (expected != 200 || List.of("list", "detail", "sales", "export").contains(endpoint)) {
            assertEquals(before, snapshot());
        }
        if (expected == 200) {
            switch (endpoint) {
                case "list" -> result.andExpect(jsonPath("$.content.length()").value(3));
                case "sales" -> result.andExpect(jsonPath("$.totalSales").value(0));
                case "export" -> result.andExpect(content().string(org.hamcrest.Matchers.containsString("101,apple,2,100,200,PENDING")));
                default -> {
                    result.andExpect(jsonPath("$.customer.id").value(1))
                            .andExpect(jsonPath("$.customer.password").doesNotExist())
                            .andExpect(jsonPath("$.customer.role").doesNotExist());
                    String target = switch (endpoint) {
                        case "ship" -> "SHIPPED";
                        case "complete" -> "COMPLETED";
                        case "cancel", "status" -> "CANCELLED";
                        default -> "PENDING";
                    };
                    result.andExpect(jsonPath("$.status").value(target));
                    assertEquals(target.equals("CANCELLED") ? 96 : 94,
                            jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Actor.class, names = {"USER_A", "USER_B", "ADMIN"})
    void realDatabaseOwnershipQueriesNeverReturnAnotherCustomersOrder(Actor actor) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            long otherId = actor == Actor.USER_B ? 101 : 102;
            assertEquals(List.of(actor.ownOrderId), orders.findOrderIdsByCustomerId(actor.customerId, org.springframework.data.domain.PageRequest.of(0, 20)).getContent());
            assertTrue(orders.findByIdAndCustomerId(actor.ownOrderId, actor.customerId).isPresent());
            assertTrue(orders.findByIdAndCustomerId(otherId, actor.customerId).isEmpty());
            assertTrue(orders.findByIdAndCustomerIdForUpdate(actor.ownOrderId, actor.customerId).isPresent());
            assertTrue(orders.findByIdAndCustomerIdForUpdate(otherId, actor.customerId).isEmpty());
            assertTrue(orders.findByIdAndCustomerIdForUpdate(999L, actor.customerId).isEmpty());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED", "CANCELLED"})
    void selfCancellationOfTerminalOrShippedOrdersReturns409WithoutRestoringStock(String state) throws Exception {
        jdbc.update("UPDATE orders SET status=? WHERE id=101", state);
        Snapshot before = snapshot();
        mvc.perform(as(Actor.USER_A, post(ME + "/101/cancel"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_ORDER_STATE_TRANSITION"));
        assertEquals(before, snapshot());
    }

    @Test
    void repeatedSelfCancelNeverRestoresStockTwice() throws Exception {
        mvc.perform(as(Actor.USER_A, post(ME + "/101/cancel"))).andExpect(status().isOk());
        Snapshot cancelled = snapshot();
        mvc.perform(as(Actor.USER_A, post(ME + "/101/cancel"))).andExpect(status().isConflict());
        assertEquals(cancelled, snapshot());
        assertEquals(96, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
    }

    @RepeatedTest(3)
    void ownerCancelAndAdministratorShipThroughJwtCannotBothSucceed() throws Exception {
        String ownerToken = com.example.demo.TestAuth.token(jwt, jdbc, "alice");
        String adminToken = com.example.demo.TestAuth.token(jwt, jdbc, "admin");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cancel = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS));
                return mvc.perform(post(ME + "/101/cancel").header("Authorization", "Bearer " + ownerToken))
                        .andReturn().getResponse().getStatus();
            });
            var ship = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS));
                return mvc.perform(post(ADMIN + "/101/ship").header("Authorization", "Bearer " + adminToken))
                        .andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of(200, 409), Stream.of(cancel.get(30, TimeUnit.SECONDS), ship.get(30, TimeUnit.SECONDS)).sorted().toList());
            String state = jdbc.queryForObject("SELECT status FROM orders WHERE id=101", String.class);
            assertTrue(state.equals("CANCELLED") || state.equals("SHIPPED"));
            assertEquals(state.equals("CANCELLED") ? 96 : 94, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
            assertEquals(1L, jdbc.queryForObject("SELECT customer_id FROM orders WHERE id=101", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM orders WHERE id=101", Long.class));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"detail", "cancel"})
    void foreignAndMissingOrdersHaveIdentical404Responses(String operation) throws Exception {
        Snapshot before = snapshot();
        var foreign = operation.equals("detail") ? get(ME + "/102") : post(ME + "/102/cancel");
        var missing = operation.equals("detail") ? get(ME + "/999") : post(ME + "/999/cancel");
        String foreignBody = mvc.perform(as(Actor.USER_A, foreign)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(as(Actor.USER_A, missing)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var foreignProblem = (ObjectNode) json.readTree(foreignBody);
        var missingProblem = (ObjectNode) json.readTree(missingBody);
        assertTrue(foreignProblem.path("instance").asText().contains("/102"));
        assertTrue(missingProblem.path("instance").asText().contains("/999"));
        foreignProblem.remove("instance");
        missingProblem.remove("instance");
        assertEquals(foreignProblem, missingProblem);
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"detail", "ship", "complete", "cancel", "status"})
    void administratorGets404ForMissingOrders(String endpoint) throws Exception {
        Snapshot before = snapshot();
        mvc.perform(as(Actor.ADMIN, adminRequest(endpoint, 999))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertEquals(before, snapshot());
    }

    @Test
    void requestBodyAndQueryCannotChooseCustomerOrGrantAdminRole() throws Exception {
        mvc.perform(as(Actor.USER_A, post(ME).header("Idempotency-Key", com.example.demo.TestOrders.key()).queryParam("role", "ROLE_ADMIN").queryParam("username", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":2,\"username\":\"bob\",\"role\":\"ROLE_ADMIN\",\"items\":[{\"productId\":1,\"quantity\":1}]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customer.id").value(1));
        mvc.perform(as(Actor.USER_A, get(ME).queryParam("customerId", "2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].customer.id").value(1)).andExpect(jsonPath("$.content[1].customer.id").value(1));
        Snapshot before = snapshot();
        mvc.perform(as(Actor.USER_A, get(ADMIN).queryParam("role", "ROLE_ADMIN"))).andExpect(status().isForbidden());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake-role-claim", "downgraded-admin", "promoted-user"})
    void jwtRoleClaimsAreIgnoredAndCurrentDatabaseRoleControlsAccess(String scenario) throws Exception {
        String username = scenario.equals("downgraded-admin") ? "admin" : "alice";
        String uid = com.example.demo.TestAuth.principal(jdbc, username).uid();
        String token = Jwts.builder().subject(uid).claim("uid", uid).claim("tokenVersion", "0")
                .issuedAt(new Date()).issuer(ISSUER).claim("role", "ROLE_ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        if (scenario.equals("downgraded-admin")) {
            jdbc.update("UPDATE users SET role='ROLE_USER' WHERE username='admin'");
        } else if (scenario.equals("promoted-user")) {
            jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username='alice'");
        }
        Snapshot before = snapshot();
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + token))
                .andExpect(status().is(scenario.equals("promoted-user") ? 200 : 403));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @EnumSource(value = Actor.class, names = {"USER_A", "USER_B"})
    void methodSecurityAlsoBlocksDirectAdminControllerInvocation(Actor actor) {
        com.example.demo.TestAuth.authenticate(jdbc, actor.username);
        Snapshot before = snapshot();
        assertThrows(AccessDeniedException.class, () -> adminController.getAll(new org.springframework.util.LinkedMultiValueMap<>()));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @EnumSource(Actor.class)
    void retiredLegacyApiCannotBypassTheNewBoundaries(Actor actor) throws Exception {
        Snapshot before = snapshot();
        int expected = actor == Actor.ANONYMOUS ? 401 : 403;
        mvc.perform(as(actor, get("/orders"))).andExpect(status().is(expected));
        mvc.perform(as(actor, post("/orders/101/cancel"))).andExpect(status().is(expected));
        assertEquals(before, snapshot());
    }

    @Test
    void existingCustomerForeignKeyProvidesAnOwnershipIndex() {
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders' AND COLUMN_NAME='customer_id' AND SEQ_IN_INDEX=1", Integer.class) > 0);
    }
}
