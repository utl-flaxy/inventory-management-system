package com.example.demo.controller;

import com.example.demo.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductNameValidationIT {
    private static final String SECRET = Base64.getEncoder()
            .encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final List<String> TABLES = List.of("inventory_movements", "order_items", "orders", "products", "users");

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("product_name_validation");

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
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id,name,price,stock,version) VALUES (1,'original',100,7,0)");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
    }

    @ParameterizedTest(name = "255 code points survive POST and PUT, case {index}")
    @MethodSource("namesAtLimit")
    void boundaryNamesArePersistedWithoutNormalization(String name) throws Exception {
        assertEquals(255, name.codePointCount(0, name.length()));
        var response = mvc.perform(nameRequest("POST", json.writeValueAsString(name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.stock").value(7))
                .andReturn().getResponse().getContentAsString();
        long createdId = json.readTree(response).path("id").asLong();
        assertEquals(name, jdbc.queryForObject("SELECT name FROM products WHERE id=?", String.class, createdId));
        assertEquals(255, jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM products WHERE id=?", Integer.class, createdId));

        mvc.perform(nameRequest("PUT", json.writeValueAsString(name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.stock").value(7));
        assertEquals(name, jdbc.queryForObject("SELECT name FROM products WHERE id=1", String.class));
        assertEquals(255, jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM products WHERE id=1", Integer.class));
        assertEquals(200L, jdbc.queryForObject("SELECT price FROM products WHERE id=1", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
        assertEquals(7, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class));
    }

    @ParameterizedTest(name = "256 code points rejected for POST and PUT, case {index}")
    @MethodSource("oversizedNames")
    void oversizedNamesReturn400WithoutChangingDatabase(String name) throws Exception {
        assertEquals(256, name.codePointCount(0, name.length()));
        assertRejectedWithoutDatabaseChanges(json.writeValueAsString(name));
    }

    @ParameterizedTest(name = "Malformed Unicode rejected for POST and PUT, case {index}")
    @ValueSource(strings = {"\\uD83D", "\\uDE00", "\\uDE00\\uD83D", "\\uD83Da"})
    void malformedNamesReturn400WithoutChangingDatabase(String escapedName) throws Exception {
        assertRejectedWithoutDatabaseChanges("\"" + escapedName + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void nullAndBlankNamesKeepExistingValidationContract(String nameJson) throws Exception {
        assertRejectedWithoutDatabaseChanges(nameJson);
    }

    @Test
    void openApiDocumentsBothRequestBoundariesAndProblemResponses() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String schema : List.of("ProductRequest", "ProductUpdateRequest")) {
            var name = document.path("components").path("schemas").path(schema).path("properties").path("name");
            assertEquals(255, name.path("maxLength").asInt(), schema);
            assertEquals(1, name.path("minLength").asInt(), schema);
            assertTrue(name.path("description").asText().contains("コードポイント"), schema);
        }
        for (var endpoint : Map.of("/products", "post", "/products/{id}", "put").entrySet()) {
            var operation = document.path("paths").path(endpoint.getKey()).path(endpoint.getValue());
            String expectedSchema = endpoint.getValue().equals("post") ? "ProductRequest" : "ProductUpdateRequest";
            assertEquals("#/components/schemas/" + expectedSchema, operation.path("requestBody").path("content")
                    .path("application/json").path("schema").path("$ref").asText());
            assertEquals("#/components/schemas/ApiProblem", operation.path("responses").path("400").path("content")
                    .path("application/problem+json").path("schema").path("$ref").asText());
        }
    }

    private void assertRejectedWithoutDatabaseChanges(String nameJson) {
        var before = snapshot();
        // Keep both requests observable even when the first assertion detects the pre-fix 500.
        assertAll("POST and PUT reject input without DB writes",
                () -> assertInvalidName("POST", nameJson), () -> assertEquals(before, snapshot()),
                () -> assertInvalidName("PUT", nameJson), () -> assertEquals(before, snapshot()));
    }

    private void assertInvalidName(String method, String nameJson) throws Exception {
        String path = method.equals("POST") ? "/products" : "/products/1";
        mvc.perform(nameRequest(method, nameJson))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", path))
                .andExpect(jsonPath("$.errors.name").isString());
    }

    private MockHttpServletRequestBuilder nameRequest(String method, String nameJson) {
        return (method.equals("POST") ? post("/products") : put("/products/1"))
                .header("Authorization", "Bearer " + jwt.generateToken("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":" + nameJson + ",\"price\":200,"
                        + (method.equals("POST") ? "\"stock\":7}" : "\"version\":0}"));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var snapshot = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return snapshot;
    }

    static Stream<String> namesAtLimit() {
        return Stream.of("a".repeat(255), "あ".repeat(255), "😀".repeat(255),
                "a".repeat(254) + "😀", "e\u0301".repeat(127) + "e");
    }

    static Stream<String> oversizedNames() {
        return Stream.of("a".repeat(256), "あ".repeat(256), "😀".repeat(256),
                "a".repeat(255) + "😀", "e\u0301".repeat(128));
    }
}
