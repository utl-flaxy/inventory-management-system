package com.example.demo.controller;

import com.example.demo.security.JwtUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.example.demo.repository.ProductRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductPagingIT {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final Set<String> PAGE_FIELDS = Set.of("content", "page", "size", "totalElements", "totalPages", "first", "last");
    private static final Set<String> PRODUCT_FIELDS = Set.of("id", "name", "price", "stock", "version");

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("product_paging");

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
    @LocalServerPort int port;
    @MockitoSpyBean ProductRepository products;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (auth_uid,id,username,password,role) VALUES (UUID_v4(),1,'alice','test-hash','ROLE_USER'), (UUID_v4(),2,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (1,' apple ',100,10), (2,' apple tart ',100,20), (3,' apple pie ',200,30), (4,'pear',300,40)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void equalPricesHaveStablePagesWithoutDuplicatesOrMissingProducts(String path) throws Exception {
        var expected = path.endsWith("/search") ? List.of(1L, 2L, 3L) : List.of(1L, 2L, 3L, 4L);
        for (int repeat = 0; repeat < 2; repeat++) {
            var ids = new ArrayList<Long>();
            for (int page = 0; page < expected.size(); page++) {
                var response = read(as("alice", request(path).param("page", String.valueOf(page))
                        .param("size", "1").param("sort", "price,asc")));
                assertEquals(PAGE_FIELDS, fields(response));
                assertEquals(page, response.path("page").asInt());
                assertEquals(1, response.path("size").asInt());
                assertEquals(expected.size(), response.path("totalElements").asLong());
                assertEquals(expected.size(), response.path("totalPages").asInt());
                assertEquals(page == 0, response.path("first").asBoolean());
                assertEquals(page == expected.size() - 1, response.path("last").asBoolean());
                assertEquals(1, response.path("content").size());
                assertEquals(PRODUCT_FIELDS, fields(response.path("content").get(0)));
                ids.add(response.path("content").get(0).path("id").asLong());
            }
            assertEquals(expected, ids);
            assertEquals(ids.size(), Set.copyOf(ids).size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void requestedIdDescendingBreaksPriceTiesInDatabase(String path) throws Exception {
        var response = read(as("alice", request(path).param("size", "2").param("sort", "price,asc", "id,desc")));
        assertEquals(2, response.path("content").get(0).path("id").asLong());
        assertEquals(1, response.path("content").get(1).path("id").asLong());
    }

    @Test
    void keywordSpacesRemainSignificant() throws Exception {
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (5,'apple',100,10)");
        var response = read(as("alice", get("/products/search").param("keyword", " apple ")));
        assertEquals(3, response.path("totalElements").asLong());
        assertEquals(List.of(1L, 2L, 3L), Stream.of(0, 1, 2)
                .map(i -> response.path("content").get(i).path("id").asLong()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u3000", "\u00a0", "\t"})
    void realMariaDbRejectsBlankKeywordWithSafe400(String keyword) throws Exception {
        mvc.perform(as("alice", get("/products/search").param("keyword", keyword)))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/search"));
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class));
    }

    @Test
    void realMariaDbUsesUnicodeCodePointLimit() throws Exception {
        var atLimit = "🍎".repeat(100);
        var response = read(as("alice", get("/products/search").param("keyword", atLimit)));
        assertEquals(0L, response.path("totalElements").asLong());
        mvc.perform(as("alice", get("/products/search").param("keyword", "🍎".repeat(101))))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/search"));
    }

    @Test
    void existingCaseInsensitiveSubstringAndWhitespaceSemanticsStayUnchanged() throws Exception {
        var upper = read(as("alice", get("/products/search").param("keyword", "APPLE")));
        assertEquals(3L, upper.path("totalElements").asLong());
        var spaces = read(as("alice", get("/products/search").param("keyword", " apple ")));
        assertEquals(3L, spaces.path("totalElements").asLong());
    }

    @Test
    void leadingWildcardSearchPlanRemainsExplicitlyUnoptimized() {
        var plan = jdbc.queryForList("EXPLAIN SELECT id FROM products WHERE name LIKE ?", "%apple%");
        assertFalse(plan.isEmpty());
        assertTrue(plan.get(0).containsKey("type")); // Inspect planning; no claim of index/rate improvement.
    }

    @Test
    void unicodeNextLineBlankIsRejectedWithRealSecurityAndMariaDb() throws Exception {
        mvc.perform(as("alice", get("/products/search").param("keyword", "\u0085\u00a0")))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/search"));
        mvc.perform(get("/products/search").param("keyword", "\u0085"))
                .andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", "/products/search"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "\\", "' OR 1=1 --"})
    void mariaDbTreatsLikeMetacharactersAndSqlTextAsLiteralSubstring(String keyword) throws Exception {
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (5,?,100,10)", "literal" + keyword + "end");
        var result = read(as("alice", get("/products/search").param("keyword", keyword)));
        assertEquals(1L, result.path("totalElements").asLong());
        assertEquals(5L, result.path("content").get(0).path("id").asLong());
    }

    static Stream<Arguments> malformedWireKeywords() {
        return Stream.of("%FF", "%ED%A0%80", "%ZZ", "%", "%C0%AF", "%E2%82")
                .flatMap(encoded -> Stream.of(Arguments.of(encoded, true), Arguments.of(encoded, false)));
    }

    @ParameterizedTest
    @MethodSource("malformedWireKeywords")
    void realTomcatRejectsMalformedQueryBeforeProductQueryAndKeepsAuthentication(String encoded, boolean authenticated)
            throws Exception {
        String authorization = authenticated ? "Authorization: Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, "alice") + "\r\n" : "";
        org.mockito.Mockito.clearInvocations(products);
        // Raw HTTP/1.0 avoids URI-client re-encoding and chunking; never log the token or response.
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var wire = "GET /products/search?keyword=" + encoded + " HTTP/1.0\r\nHost: localhost\r\n"
                    + authorization + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var parts = response.split("\r\n\r\n", 2);
            assertEquals(authenticated ? "400" : "401", parts[0].split(" ")[1]);
            assertTrue(parts[0].toLowerCase(java.util.Locale.ROOT).contains("application/problem+json"));
            var body = json.readTree(parts[1]);
            assertEquals(6, body.size());
            assertEquals(authenticated ? "INVALID_REQUEST" : "UNAUTHENTICATED", body.path("code").asText());
            assertEquals(authenticated ? com.example.demo.exception.ApiProblemFactory.INVALID_REQUEST_DETAIL
                    : com.example.demo.exception.ApiProblemFactory.UNAUTHENTICATED_DETAIL, body.path("detail").asText());
            assertEquals("/products/search", body.path("instance").asText());
        }
        org.mockito.Mockito.verifyNoInteractions(products);
    }

    static Stream<Arguments> actors() {
        return Stream.of("/products", "/products/search").flatMap(path ->
                Stream.of(Arguments.of(path, "", 401), Arguments.of(path, "alice", 200), Arguments.of(path, "admin", 200)));
    }

    @ParameterizedTest
    @MethodSource("actors")
    void existingAnonymousUserAdminAuthorizationIsPreserved(String path, String actor, int statusCode) throws Exception {
        var request = request(path);
        if (!actor.isEmpty()) as(actor, request);
        var result = mvc.perform(request).andExpect(status().is(statusCode));
        if (statusCode == 401) result.andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void productServiceLimitsPageSize(String path) throws Exception {
        var response = read(as("alice", request(path).param("size", "500")));
        assertEquals(100, response.path("size").asInt());
    }

    @Test
    void inventoryPageSizeKeepsMainBehaviorOutsideProductLimit() throws Exception {
        var path = "/api/v1/admin/products/1/inventory-movements";
        var requested = read(as("admin", get(path).param("size", "500")));
        assertEquals(500, requested.path("size").asInt());
        var capped = read(as("admin", get(path).param("size", "5000")));
        assertEquals(2000, capped.path("size").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void openApiDocumentsParametersTypedPageAndSafe400(String path) throws Exception {
        var api = read(get("/v3/api-docs"));
        var operation = api.path("paths").path(path).path("get");
        var parameters = new java.util.HashMap<String, JsonNode>();
        operation.path("parameters").forEach(parameter -> parameters.put(parameter.path("name").asText(), parameter));
        assertTrue(parameters.keySet().containsAll(Set.of("page", "size", "sort")));
        if (path.equals("/products/search")) {
            assertTrue(parameters.containsKey("keyword"));
            var keywordSchema = parameters.get("keyword").path("schema");
            assertEquals(1, keywordSchema.path("minLength").asInt());
            assertEquals(100, keywordSchema.path("maxLength").asInt());
            assertTrue(parameters.get("keyword").path("description").asText().contains("nonblank"));
        }
        assertEquals(0, parameters.get("page").path("schema").path("default").asInt(-1));
        assertEquals(20, parameters.get("size").path("schema").path("default").asInt());
        assertEquals(100, parameters.get("size").path("schema").path("maximum").asInt());
        assertTrue(parameters.get("page").path("description").asText().contains("0-based"));
        assertTrue(parameters.get("page").path("description").asText().contains("2147483647"));
        var sortDescription = parameters.get("sort").path("description").asText();
        for (String field : PRODUCT_FIELDS) assertTrue(sortDescription.contains(field), field);
        assertTrue(sortDescription.contains("sort=price,asc&sort=name,desc"));
        assertEquals("array", parameters.get("sort").path("schema").path("type").asText());

        var responseSchema = operation.path("responses").path("200").path("content").values().iterator().next().path("schema");
        assertTrue(responseSchema.path("$ref").asText().contains("PageResponse"));
        var page = resolve(api, responseSchema);
        assertEquals(PAGE_FIELDS, fields(page.path("properties")));
        var product = resolve(api, page.path("properties").path("content").path("items"));
        assertEquals(PRODUCT_FIELDS, fields(product.path("properties")));
        assertEquals("#/components/schemas/ApiProblem", operation.path("responses").path("400")
                .path("content").path("application/problem+json").path("schema").path("$ref").asText());
    }

    private MockHttpServletRequestBuilder request(String path) { return get(path).param("keyword", "apple"); }

    private MockHttpServletRequestBuilder as(String actor, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, actor));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode resolve(JsonNode api, JsonNode schema) {
        return schema.has("$ref") ? api.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private Set<String> fields(JsonNode node) {
        var fields = new TreeSet<String>();
        node.propertyNames().forEach(fields::add);
        return fields;
    }
}
