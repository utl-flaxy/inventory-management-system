package com.example.demo.controller;

import com.example.demo.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
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

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER'), (2,'admin','test-hash','ROLE_ADMIN')");
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
    void realResolverUsesCommonLimit(String path) throws Exception {
        var response = read(as("alice", request(path).param("size", "500")));
        assertEquals(100, response.path("size").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void openApiDocumentsParametersTypedPageAndSafe400(String path) throws Exception {
        var api = read(get("/v3/api-docs"));
        var operation = api.path("paths").path(path).path("get");
        var parameters = new java.util.HashMap<String, JsonNode>();
        operation.path("parameters").forEach(parameter -> parameters.put(parameter.path("name").asText(), parameter));
        assertTrue(parameters.keySet().containsAll(Set.of("page", "size", "sort")));
        assertEquals(0, parameters.get("page").path("schema").path("default").asInt(-1));
        assertEquals(20, parameters.get("size").path("schema").path("default").asInt());
        assertEquals(100, parameters.get("size").path("schema").path("maximum").asInt());
        assertTrue(parameters.get("page").path("description").asText().contains("0-based"));
        var sortDescription = parameters.get("sort").path("description").asText();
        for (String field : PRODUCT_FIELDS) assertTrue(sortDescription.contains(field), field);
        assertTrue(sortDescription.contains("sort=price,asc&sort=name,desc"));
        assertEquals("array", parameters.get("sort").path("schema").path("type").asText());

        var responseSchema = operation.path("responses").path("200").path("content").elements().next().path("schema");
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
        return request.header("Authorization", "Bearer " + jwt.generateToken(actor));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode resolve(JsonNode api, JsonNode schema) {
        return schema.has("$ref") ? api.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private Set<String> fields(JsonNode node) {
        var fields = new TreeSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
