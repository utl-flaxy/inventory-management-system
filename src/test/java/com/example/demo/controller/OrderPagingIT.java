package com.example.demo.controller;

import com.example.demo.repository.OrderRepository;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.open-in-view=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false", "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(OrderPagingIT.QueryCaptureConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderPagingIT {
    private static final String ADMIN = "/api/v1/admin/orders", ME = "/api/v1/me/orders";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final Set<String> PAGE_FIELDS = Set.of("content", "page", "size", "totalElements", "totalPages", "first", "last");
    private static final Set<String> ORDER_FIELDS = Set.of("id", "status", "orderedAt", "updatedAt", "completedAt",
            "totalPrice", "version", "customer", "items");
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("order_paging");

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
    @Autowired OrderService service;
    @Autowired EntityManagerFactory emf;
    @Autowired DataSource datasource;
    @MockitoSpyBean OrderRepository orders;

    @BeforeEach
    void fixtures() {
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER'),"
                + "(2,'bob','test-hash','ROLE_USER'),(3,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (1,'renamed',900,1000),(2,'other',100,1000)");
        addOrder(101, 1); addOrder(102, 1); addOrder(103, 2); addOrder(104, 1); addOrder(105, 3);
        SqlCapture.clear();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        SqlCapture.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void defaultContractAndHistoricalSnapshotArePreserved(String path) throws Exception {
        var response = read(request(path));
        assertEquals(PAGE_FIELDS, fields(response));
        assertEquals(0, response.path("page").asInt());
        assertEquals(20, response.path("size").asInt());
        assertEquals(path.equals(ADMIN) ? List.of(105L, 104L, 103L, 102L, 101L) : List.of(104L, 102L, 101L), ids(response));
        assertEquals(path.equals(ADMIN) ? 5 : 3, response.path("totalElements").asLong());
        for (var order : response.path("content")) {
            assertEquals(ORDER_FIELDS, fields(order));
            assertEquals(Set.of("id", "username"), fields(order.path("customer")));
            assertEquals(6_000_000_100L, order.path("totalPrice").asLong());
            assertEquals("PENDING", order.path("status").asText());
            assertEquals("2026-10-01T00:00:00Z", order.path("orderedAt").asText());
            assertTrue(order.path("completedAt").isNull());
            assertEquals(0, order.path("version").asLong());
            var item = order.path("items").get(0);
            assertEquals(Set.of("productId", "productName", "unitPrice", "quantity", "lineTotal"), fields(item));
            assertEquals("日本語 保存名", item.path("productName").asText());
            assertEquals(1, item.path("productId").asLong());
            assertEquals(3_000_000_000L, item.path("unitPrice").asLong());
            assertEquals(2, item.path("quantity").asInt());
            assertEquals(6_000_000_000L, item.path("lineTotal").asLong());
            assertEquals("second", order.path("items").get(1).path("productName").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void threePagesAreOrderBoundariesDespiteMultipleItemsAndTies(String path) throws Exception {
        List<Long> expected = path.equals(ADMIN) ? List.of(105L, 104L, 103L, 102L, 101L) : List.of(104L, 102L, 101L);
        int size = path.equals(ADMIN) ? 2 : 1;
        for (int repeat = 0; repeat < 2; repeat++) {
            var found = new ArrayList<Long>();
            for (int page = 0; page < 3; page++) {
                var response = read(request(path).param("page", String.valueOf(page)).param("size", String.valueOf(size))
                        .param("sort", "orderedAt,asc"));
                found.addAll(ids(response));
                assertEquals(expected.size(), response.path("totalElements").asLong());
                assertEquals(3, response.path("totalPages").asInt());
                assertEquals(page == 0, response.path("first").asBoolean());
                assertEquals(page == 2, response.path("last").asBoolean());
                for (var order : response.path("content")) assertEquals(2, order.path("items").size());
            }
            assertEquals(expected, found);
            assertEquals(found.size(), Set.copyOf(found).size());
        }
    }

    static Stream<Arguments> directions() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of("id", "orderedAt", "status", "totalPrice").flatMap(field ->
                Stream.of(Arguments.of(path, field, "asc"), Arguments.of(path, field, "desc"))));
    }

    @ParameterizedTest
    @MethodSource("directions")
    void allAllowedSortsRunInDatabaseWithStableTieBreaker(String path, String field, String direction) throws Exception {
        var response = read(request(path).param("sort", field + "," + direction));
        var expected = new ArrayList<>(path.equals(ADMIN) ? List.of(105L, 104L, 103L, 102L, 101L) : List.of(104L, 102L, 101L));
        if (field.equals("id") && direction.equals("asc")) java.util.Collections.reverse(expected);
        assertEquals(expected, ids(response));
        assertEquals(expected.size(), response.path("totalElements").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void compoundSortDoesNotOverrideExplicitId(String path) throws Exception {
        jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id=102");
        var response = read(request(path).param("sort", "status,asc", "totalPrice,desc", "id,asc"));
        assertEquals(path.equals(ADMIN) ? List.of(101L, 103L, 104L, 105L, 102L) : List.of(101L, 104L, 102L), ids(response));
    }

    static Stream<Arguments> actors() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of(Arguments.of(path, "", 401),
                Arguments.of(path, "alice", path.equals(ADMIN) ? 403 : 200), Arguments.of(path, "admin", 200)));
    }

    static Stream<Arguments> distinctSortValues() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of("orderedAt", "status", "totalPrice").flatMap(field ->
                Stream.of(Arguments.of(path, field, "asc"), Arguments.of(path, field, "desc"))));
    }

    @ParameterizedTest
    @MethodSource("distinctSortValues")
    void ascendingAndDescendingSortsOrderDistinctValuesBeforeIdTies(String path, String field, String direction) throws Exception {
        switch (field) {
            case "orderedAt" -> {
                jdbc.update("UPDATE orders SET ordered_at='2026-09-01' WHERE id=101");
                jdbc.update("UPDATE orders SET ordered_at='2026-11-01' WHERE id=102");
            }
            case "status" -> {
                jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=101");
                jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id=102");
            }
            case "totalPrice" -> {
                jdbc.update("UPDATE order_items SET quantity=1 WHERE order_id=101 AND product_id=1");
                jdbc.update("UPDATE orders SET total_price=3000000100 WHERE id=101");
                jdbc.update("UPDATE order_items SET quantity=3 WHERE order_id=102 AND product_id=1");
                jdbc.update("UPDATE orders SET total_price=9000000100 WHERE id=102");
            }
            default -> fail();
        }
        var expected = new ArrayList<Long>();
        expected.add(direction.equals("asc") ? 101L : 102L);
        expected.addAll(path.equals(ADMIN) ? List.of(105L, 104L, 103L) : List.of(104L));
        expected.add(direction.equals("asc") ? 102L : 101L);
        assertEquals(expected, ids(read(request(path).param("sort", field + "," + direction))));
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of(Arguments.of(path, "page", "-1"),
                Arguments.of(path, "page", "abc"), Arguments.of(path, "size", "0"),
                Arguments.of(path, "size", "-1"), Arguments.of(path, "size", "abc"),
                Arguments.of(path, "sort", "customer.password,asc"), Arguments.of(path, "sort", "id,sideways"),
                Arguments.of(path, "sort", ",asc")));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidRequestsAre400AndNeverQueryOrders(String path, String parameter, String value) throws Exception {
        SqlCapture.clear();
        mvc.perform(request(path).param(parameter, value)).andExpect(status().isBadRequest())
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", path));
        assertTrue(SqlCapture.sql().stream().noneMatch(sql -> sql.contains(" from orders ")));
    }

    @Test
    void successfulReadsDoNotChangeAnyStoredBusinessData() throws Exception {
        var before = databaseSnapshot();
        read(request(ADMIN)); read(request(ME).param("size", "1")); read(request(ME).param("page", "99"));
        assertEquals(before, databaseSnapshot());
    }

    @ParameterizedTest
    @MethodSource("actors")
    void roleMatrixIsUnchanged(String path, String actor, int expected) throws Exception {
        var request = get(path);
        if (!actor.isEmpty()) request.header("Authorization", "Bearer " + jwt.generateToken(actor));
        var result = mvc.perform(request).andExpect(status().is(expected));
        if (expected == 401) result.andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", path));
        if (expected == 403) result.andExpect(problem(403, "ACCESS_DENIED", "Access Denied", path));
        if (expected == 200 && path.equals(ME))
            result.andExpect(jsonPath("$.totalElements").value(actor.equals("alice") ? 3 : 1));
    }

    @Test
    void selfQueriesAlwaysFilterContentAndCountByAuthenticatedCustomer() throws Exception {
        SqlCapture.clear();
        var response = read(request(ME).param("customerId", "2").param("username", "bob").param("size", "1"));
        assertEquals(List.of(104L), ids(response));
        assertEquals(3, response.path("totalElements").asLong());
        var orderSql = SqlCapture.sql().stream().filter(sql -> sql.contains(" from orders ")).toList();
        assertEquals(3, orderSql.size());
        for (String sql : orderSql) {
            String predicate = sql.substring(sql.indexOf(" where "));
            assertTrue(predicate.contains("customer_id=?") || predicate.contains("c1_0.id=?"), sql);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void emptyAndOutOfRangePagesAre200WithAccurateTotals(String path) throws Exception {
        var outside = read(request(path).param("page", "99").param("size", "1"));
        assertTrue(ids(outside).isEmpty());
        assertEquals(path.equals(ADMIN) ? 5 : 3, outside.path("totalElements").asLong());
        assertEquals(99, outside.path("page").asInt());
        assertFalse(outside.path("first").asBoolean());
        assertTrue(outside.path("last").asBoolean());
        jdbc.update("DELETE FROM order_items"); jdbc.update("DELETE FROM orders");
        var empty = read(request(path));
        assertTrue(ids(empty).isEmpty());
        assertEquals(0, empty.path("totalElements").asLong());
        assertEquals(0, empty.path("totalPages").asInt());
        assertTrue(empty.path("first").asBoolean()); assertTrue(empty.path("last").asBoolean());
    }

    static Stream<Arguments> sizes() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of(1, 20, 100, 101).map(size -> Arguments.of(path, size)));
    }

    @ParameterizedTest
    @MethodSource("sizes")
    void realQueryCountIsConstantAndOnlyRequestedOrdersAreMaterialized(String path, int size) throws Exception {
        for (long id = 106; id <= 226; id++) addOrder(id, 1);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear(); SqlCapture.clear();
        var response = read(request(path).param("size", String.valueOf(size)));
        assertEquals(Math.min(size, 100), response.path("size").asInt());
        assertEquals(Math.min(size, 100), response.path("content").size());
        assertEquals(path.equals(ADMIN) ? 126 : 124, response.path("totalElements").asLong());
        var sql = SqlCapture.sql();
        var orderQueries = sql.stream().filter(query -> query.contains(" from orders ")).toList();
        assertEquals(3, orderQueries.size(), "ID page + count + one bounded detail query");
        assertEquals(path.equals(ADMIN) ? 4 : 5, statistics.getPrepareStatementCount(), "Includes JWT/user lookup");
        assertEquals(0, statistics.getCollectionFetchCount());
        assertTrue(statistics.getEntityLoadCount() <= 2, "Only authentication users; orders/items are scalar DTOs");
        assertEquals(0, statistics.getEntityInsertCount()); assertEquals(0, statistics.getEntityUpdateCount());
        assertEquals(0, statistics.getEntityDeleteCount());
        String details = orderQueries.stream().filter(query -> query.contains("join order_items")).findFirst().orElseThrow();
        assertTrue(details.contains(" in ("));
        assertFalse(details.contains("password")); assertFalse(details.contains("idempotency_key"));
        assertFalse(details.contains("join products"));
        assertFalse(details.contains("limit"), "Pagination belongs to root ID query only");
        String root = orderQueries.stream().filter(query -> query.contains("limit")).findFirst().orElseThrow();
        assertFalse(root.contains("join order_items"));
        System.out.printf("ORDER_PAGING_QUERY path=%s size=%d orderQueries=%d totalQueries=%d entities=%d collectionFetches=%d%n",
                path, size, orderQueries.size(), statistics.getPrepareStatementCount(),
                statistics.getEntityLoadCount(), statistics.getCollectionFetchCount());
    }

    @Test
    void idPageAndDetailsShareReadOnlyRepeatableReadSnapshotWithoutOsiv() throws Exception {
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Connection bound = DataSourceUtils.getConnection(datasource);
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, bound.getTransactionIsolation());
            assertTrue(bound.isReadOnly());
            // 別connectionでcommit。非locking snapshot readはwriteを止めない。
            try (Connection concurrent = datasource.getConnection(); var statement = concurrent.createStatement()) {
                statement.executeUpdate("UPDATE orders SET status='SHIPPED', version=version+1 WHERE id=105");
                statement.executeUpdate("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version) "
                        + "VALUES (106,1,0,'PENDING','2026-10-01','2026-10-01',0)");
            }
            return mockingDetails(orders).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(orders).findPageRows(anyList());
        var response = service.findAll(PageRequest.of(0, 2, Sort.Direction.DESC, "id"));
        assertEquals(5, response.getTotalElements());
        assertEquals(List.of(105L, 104L), response.map(order -> order.id()).getContent());
        assertEquals("PENDING", response.getContent().get(0).status().name());
        assertEquals(0, response.getContent().get(0).version());
        assertEquals("SHIPPED", jdbc.queryForObject("SELECT status FROM orders WHERE id=105", String.class));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void flywayIndexesSupportRootOwnershipAndBoundedDetailReads() {
        var root = jdbc.queryForMap("EXPLAIN SELECT id FROM orders ORDER BY id DESC LIMIT 20");
        assertEquals("PRIMARY", root.get("key"));
        var own = jdbc.queryForMap("EXPLAIN SELECT id FROM orders WHERE customer_id=1 ORDER BY id DESC LIMIT 20");
        assertEquals("idx_orders_customer", own.get("key"));
        var items = jdbc.queryForMap("EXPLAIN SELECT id,order_id FROM order_items WHERE order_id IN (101,102) ORDER BY order_id,id");
        assertEquals("idx_order_items_order", items.get("key"));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        System.out.println("ORDER_PAGING_EXPLAIN root=PRIMARY self=idx_orders_customer details=idx_order_items_order migrations=6");
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void openApiDocumentsBreakingTypedPageContractAndStrictParameters(String path) throws Exception {
        var api = read(get("/v3/api-docs"));
        var operation = api.path("paths").path(path).path("get");
        assertTrue(operation.path("description").asText().contains("破壊的変更"));
        var parameters = new java.util.HashMap<String, JsonNode>();
        operation.path("parameters").forEach(parameter -> parameters.put(parameter.path("name").asText(), parameter));
        assertEquals(Set.of("page", "size", "sort"), parameters.keySet());
        assertEquals(0, parameters.get("page").path("schema").path("default").asInt());
        assertEquals(20, parameters.get("size").path("schema").path("default").asInt());
        assertEquals(100, parameters.get("size").path("schema").path("maximum").asInt());
        assertTrue(parameters.get("sort").path("description").asText().contains("totalPrice"));
        assertTrue(parameters.get("sort").path("description").asText().contains("id DESC"));
        var ref = operation.path("responses").path("200").path("content").values().iterator().next().path("schema").path("$ref").asText();
        var pageSchema = api.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
        assertEquals(PAGE_FIELDS, fields(pageSchema.path("properties")));
        assertEquals("#/components/schemas/OrderResponse", pageSchema.path("properties").path("content").path("items").path("$ref").asText());
        assertEquals("#/components/schemas/ApiProblem", operation.path("responses").path("400").path("content")
                .path("application/problem+json").path("schema").path("$ref").asText());
    }

    private void addOrder(long id, long customer) {
        jdbc.update("INSERT INTO orders (id,customer_id,total_price,status,ordered_at,updated_at,version) "
                + "VALUES (?,?,6000000100,'PENDING','2026-10-01','2026-10-01',0)", id, customer);
        jdbc.update("INSERT INTO order_items (order_id,product_id,quantity,price,product_name_snapshot) VALUES (?,1,2,3000000000,'日本語 保存名'),(?,2,1,100,'second')", id, id);
    }

    private List<List<java.util.Map<String, Object>>> databaseSnapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM orders ORDER BY id"),
                jdbc.queryForList("SELECT * FROM order_items ORDER BY id"),
                jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY id"));
    }

    private MockHttpServletRequestBuilder request(String path) {
        return get(path).header("Authorization", "Bearer " + jwt.generateToken(path.equals(ADMIN) ? "admin" : "alice"));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private List<Long> ids(JsonNode response) {
        var result = new ArrayList<Long>();
        response.path("content").forEach(order -> result.add(order.path("id").asLong()));
        return result;
    }

    private Set<String> fields(JsonNode node) {
        var result = new TreeSet<String>(); node.propertyNames().forEach(result::add); return result;
    }

    static final class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> QUERIES = ThreadLocal.withInitial(ArrayList::new);
        public String inspect(String sql) { QUERIES.get().add(sql); return sql; }
        static List<String> sql() { return List.copyOf(QUERIES.get()); }
        static void clear() { QUERIES.remove(); }
    }

    @TestConfiguration
    static class QueryCaptureConfiguration {
        @Bean HibernatePropertiesCustomizer queryCapture() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", new SqlCapture());
        }
    }
}
