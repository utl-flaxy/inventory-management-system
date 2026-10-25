package com.example.demo.controller;

import com.example.demo.TestAuth;
import com.example.demo.exception.ApiProblemFactory;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PageOffsetIT {
    private static final String HISTORY = "/api/v1/admin/products/1/inventory-movements";
    private static final String ORDERS = "/api/v1/me/orders";
    private static final List<String> TARGETS = List.of("/products", "/products/search?keyword=apple", HISTORY);
    private static final Set<String> PAGE_FIELDS = Set.of(
            "content", "page", "size", "totalElements", "totalPages", "first", "last");
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code");
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("page_offset");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private Map<String, List<Map<String, Object>>> before;

    @BeforeEach
    void fixtures() {
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES "
                + "(UUID_v4(),1,'alice','unused-test-hash','ROLE_USER'),"
                + "(UUID_v4(),2,'admin','unused-test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES "
                + "(1,'apple',100,10,0),(2,'apple pie',200,5,0)");
        before = snapshot();
    }

    @AfterEach
    void readsNeverChangeBusinessData() {
        assertEquals(before, snapshot());
    }

    static Stream<Arguments> overflowingOffsets() {
        var ordinary = TARGETS.stream().flatMap(path -> Stream.of(
                Arguments.of(path, Integer.MAX_VALUE, 2),
                Arguments.of(path, Integer.MAX_VALUE, 20),
                Arguments.of(path, Integer.MAX_VALUE / 2 + 1, 2),
                Arguments.of(path, Integer.MAX_VALUE / 20 + 1, 20)));
        var capped = Stream.of(
                Arguments.of("/products", Integer.MAX_VALUE / 100 + 1, 500),
                Arguments.of("/products/search?keyword=apple", Integer.MAX_VALUE / 100 + 1, 500),
                Arguments.of(HISTORY, Integer.MAX_VALUE / 2000 + 1, 5000));
        return Stream.concat(Stream.concat(ordinary, capped), Stream.of(Arguments.of(ORDERS, Integer.MAX_VALUE, 20)));
    }

    @ParameterizedTest
    @MethodSource("overflowingOffsets")
    void unrepresentableOffsetIsSafe400BeforeDatabasePagination(String path, int page, int size) throws Exception {
        var response = get(path, "page=" + page + "&size=" + size, actor(path));
        var body = read(response, path);
        // Only public status/code and public numeric input are recorded; never headers, JWT or raw response.
        System.out.printf("ISSUE158 api=%s page=%d size=%d status=%d code=%s problemFields=%d%n",
                URI.create(path).getPath(), page, size, response.statusCode(), body.path("code").asText(), body.size());
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", body.path("code").asText());
    }

    static Stream<Arguments> representableBoundaries() {
        return Stream.concat(TARGETS.stream().flatMap(path -> Stream.of(
                Arguments.of(path, Integer.MAX_VALUE, 1),
                Arguments.of(path, Integer.MAX_VALUE / 2, 2),
                Arguments.of(path, Integer.MAX_VALUE / 20, 20))),
                Stream.of(Arguments.of(ORDERS, Integer.MAX_VALUE, 1)));
    }

    @ParameterizedTest
    @MethodSource("representableBoundaries")
    void largestRepresentableOffsetsStillReturnTheExistingEmptyPage(String path, int page, int size) throws Exception {
        var response = get(path, "page=" + page + "&size=" + size, actor(path));
        var body = read(response, path);
        assertEquals(200, response.statusCode());
        assertEquals(PAGE_FIELDS, fields(body));
        assertEquals(page, body.path("page").asInt());
        assertEquals(size, body.path("size").asInt());
        assertTrue(body.path("content").isArray());
        assertEquals(0, body.path("content").size());
        assertEquals(path.startsWith("/products") ? 2 : 0, body.path("totalElements").asLong());
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "not-a-number,wrong", "0,0"})
    void standardResolverStillDefaultsMalformedPageAndSize(String page, String size) throws Exception {
        for (String path : TARGETS) {
            var response = get(path, "page=" + page + "&size=" + size, actor(path));
            var body = read(response, path);
            assertEquals(200, response.statusCode());
            assertEquals(0, body.path("page").asInt());
            assertEquals(20, body.path("size").asInt());
        }
    }

    static Stream<Arguments> cappedBoundaries() {
        return Stream.of(Arguments.of("/products", 500, 100),
                Arguments.of("/products/search?keyword=apple", 500, 100),
                Arguments.of(HISTORY, 5000, 2000));
    }

    @ParameterizedTest
    @MethodSource("cappedBoundaries")
    void offsetUsesNormalizedSizeRatherThanTheLargerRequestedSize(String path, int requested, int normalized)
            throws Exception {
        int page = Integer.MAX_VALUE / normalized;
        var response = get(path, "page=" + page + "&size=" + requested, actor(path));
        var body = read(response, path);
        assertEquals(200, response.statusCode());
        assertEquals(page, body.path("page").asInt());
        assertEquals(normalized, body.path("size").asInt());
        assertEquals(0, body.path("content").size());
    }

    static Stream<String> targets() { return TARGETS.stream(); }

    @ParameterizedTest
    @MethodSource("targets")
    void existingEndpointSpecificSizeCapsAndSortPoliciesArePreserved(String path) throws Exception {
        var capped = get(path, "size=5000", actor(path));
        assertEquals(200, capped.statusCode());
        assertEquals(path.equals(HISTORY) ? 2000 : 100, read(capped, path).path("size").asInt());
        var sorted = get(path, "sort=password,desc", actor(path));
        var body = read(sorted, path);
        assertEquals(path.equals(HISTORY) ? 200 : 400, sorted.statusCode());
        if (!path.equals(HISTORY)) assertEquals("INVALID_REQUEST", body.path("code").asText());
    }

    @ParameterizedTest
    @MethodSource("targets")
    void anonymousRequestsRemain401EvenWithAnOverflowingOffset(String path) throws Exception {
        var response = get(path, "page=2147483647&size=20", null);
        assertEquals(401, response.statusCode());
        assertEquals("UNAUTHENTICATED", read(response, path).path("code").asText());
    }

    @Test
    void historyStillRequiresAdminBeforePaginationValidation() throws Exception {
        var response = get(HISTORY, "page=2147483647&size=20", "alice");
        assertEquals(403, response.statusCode());
        assertEquals("ACCESS_DENIED", read(response, HISTORY).path("code").asText());
    }

    @Test
    void missingProductStillReturns404BeforeHistoryPagination() throws Exception {
        String path = "/api/v1/admin/products/999/inventory-movements";
        var response = get(path, "page=2147483647&size=20", "admin");
        assertEquals(404, response.statusCode());
        assertEquals("PRODUCT_NOT_FOUND", read(response, path).path("code").asText());
    }

    private String actor(String path) { return path.equals(HISTORY) ? "admin" : "alice"; }

    private HttpResponse<String> get(String path, String query, String actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path
                + (path.contains("?") ? "&" : "?") + query)).timeout(Duration.ofSeconds(10)).GET();
        if (actor != null) request.header("Authorization", "Bearer " + TestAuth.token(jwt, jdbc, actor));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(HttpResponse<String> response, String path) throws Exception {
        JsonNode body = json.readTree(response.body());
        if (response.statusCode() >= 400) {
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
            assertEquals(PROBLEM_FIELDS, fields(body));
            assertEquals(response.statusCode(), body.path("status").asInt());
            String code = switch (response.statusCode()) {
                case 400 -> "INVALID_REQUEST";
                case 401 -> "UNAUTHENTICATED";
                case 403 -> "ACCESS_DENIED";
                case 404 -> "PRODUCT_NOT_FOUND";
                case 500 -> "SYSTEM_ERROR";
                default -> throw new AssertionError("Unexpected HTTP status: " + response.statusCode());
            };
            String detail = switch (code) {
                case "INVALID_REQUEST" -> ApiProblemFactory.INVALID_REQUEST_DETAIL;
                case "UNAUTHENTICATED" -> ApiProblemFactory.UNAUTHENTICATED_DETAIL;
                case "ACCESS_DENIED" -> ApiProblemFactory.ACCESS_DENIED_DETAIL;
                case "PRODUCT_NOT_FOUND" -> new ProductNotFoundException().getMessage();
                default -> ApiProblemFactory.SYSTEM_ERROR_DETAIL;
            };
            assertEquals(code, body.path("code").asText());
            assertEquals(ApiProblemFactory.TYPE_BASE + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    body.path("type").asText());
            assertEquals(ApiProblemFactory.title(code), body.path("title").asText());
            assertEquals(detail, body.path("detail").asText());
            assertEquals(URI.create(path).getPath(), body.path("instance").asText());
            assertFalse(response.headers().firstValue("X-Request-Id").orElse("").isBlank());
        }
        return body;
    }

    private Set<String> fields(JsonNode node) {
        var fields = new TreeSet<String>();
        node.propertyNames().forEach(fields::add);
        return fields;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("products", "orders", "order_items", "inventory_movements"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        result.put("users", jdbc.queryForList("SELECT id,role,token_version FROM users ORDER BY id"));
        return result;
    }
}
