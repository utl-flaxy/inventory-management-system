package com.example.demo.controller;

import com.example.demo.security.JwtUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalesApiIT {
    private static final String ADMIN = "/api/v1/admin/orders";
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("sales_api");

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
    @MockBean private Clock clock;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO users (id,username,password,role) VALUES (1,'alice','test-hash','ROLE_USER'),(2,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products (id,name,price,stock) VALUES (1,'apple',100,100)");
        when(clock.instant()).thenReturn(FROM);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (String table : List.of("order_items", "orders", "products", "users")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void revenueAppearsOnlyAtCompletionAndRepeatingCompletionIs409WithNoDatabaseChange() throws Exception {
        long id = createOrder();
        assertSales(get(ADMIN + "/sales"), 0, 0);
        command(id, "ship").andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()));
        assertSales(get(ADMIN + "/sales"), 0, 0);
        Instant at = Instant.parse("2026-10-15T12:00:00.123456789Z");
        when(clock.instant()).thenReturn(at);
        command(id, "complete").andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").value("2026-10-15T12:00:00.123456Z"));
        var completed = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", id);
        when(clock.instant()).thenReturn(TO);
        command(id, "complete").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_ORDER_STATE_TRANSITION"));
        assertEquals(completed, jdbc.queryForMap("SELECT * FROM orders WHERE id=?", id));
        assertEquals(99, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
        assertSales(get(ADMIN + "/sales"), 100, 1);
    }

    @Test
    void cancelledOrderKeepsNullCompletionTimeRestoresStockAndContributesNothing() throws Exception {
        long id = createOrder();
        command(id, "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()));
        assertSales(get(ADMIN + "/sales"), 0, 0);
        assertEquals(100, jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"null,null,300,3", "2026-10-01T00:00:00Z,null,300,3",
            "null,2026-11-01T00:00:00Z,200,2", "2026-10-01T00:00:00Z,2026-11-01T00:00:00Z,200,2",
            "2026-10-01T09:00:00+09:00,2026-11-01T09:00:00+09:00,200,2"})
    void periodParametersAreOptionalAndAcceptIsoInstantsWithOffsets(String lower, String upper, long amount, long count) throws Exception {
        for (Instant at : List.of(FROM, Instant.parse("2026-10-15T12:00:00Z"), TO)) {
            long id = createOrder();
            command(id, "ship").andExpect(status().isOk());
            when(clock.instant()).thenReturn(at);
            command(id, "complete").andExpect(status().isOk());
        }
        MockHttpServletRequestBuilder request = get(ADMIN + "/sales");
        if (!"null".equals(lower)) request.param("from", lower);
        if (!"null".equals(upper)) request.param("to", upper);
        assertSales(request, amount, count);
    }

    @ParameterizedTest
    @CsvSource({"from,not-a-date", "to,not-a-date", "from,2026-10-01T00:00:00", "to,2026-13-01T00:00:00Z"})
    void malformedOrTimezoneAmbiguousDateIs400WithExistingMapContract(String parameter, String value) throws Exception {
        mvc.perform(get(ADMIN + "/sales").param(parameter, value).header("Authorization", adminToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @CsvSource({"2026-11-01T00:00:00Z,2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z,2026-10-01T00:00:00Z"})
    void reversedOrEmptyRangeIs400(String from, String to) throws Exception {
        mvc.perform(get(ADMIN + "/sales").param("from", from).param("to", to).header("Authorization", adminToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
    }

    @Test
    void salesRemainAdministratorOnly() throws Exception {
        mvc.perform(get(ADMIN + "/sales").param("from", FROM.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN + "/sales").header("Authorization", "Bearer " + jwt.generateToken("alice")))
                .andExpect(status().isForbidden());
        assertSales(get(ADMIN + "/sales"), 0, 0);
    }

    @Test
    void legacyCompletedWithUnknownTimeRemainsReadableAndExcludedFromSales() throws Exception {
        long id = createOrder();
        jdbc.update("UPDATE orders SET status='COMPLETED',completed_at=NULL WHERE id=?", id);
        mvc.perform(get(ADMIN + "/" + id).header("Authorization", adminToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()));
        assertSales(get(ADMIN + "/sales"), 0, 0);
        assertSales(get(ADMIN + "/sales").param("from", FROM.toString()).param("to", TO.toString()), 0, 0);
        assertNull(jdbc.queryForObject("SELECT completed_at FROM orders WHERE id=?", java.sql.Timestamp.class, id));
    }

    @Test
    void openApiDescribesNullableCompletionTimeAndOptionalDateTimeBounds() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var completion = document.path("components").path("schemas").path("OrderResponse").path("properties").path("completedAt");
        assertTrue(completion.path("type").isArray());
        assertEquals(Set.of("string", "null"), json.convertValue(completion.path("type"), new TypeReference<Set<String>>() { }));
        assertEquals("date-time", completion.path("format").asText());
        var parameters = document.path("paths").path(ADMIN + "/sales").path("get").path("parameters");
        assertEquals(2, parameters.size());
        for (var parameter : parameters) {
            assertTrue(List.of("from", "to").contains(parameter.path("name").asText()));
            assertEquals("query", parameter.path("in").asText());
            assertFalse(parameter.path("required").asBoolean());
            assertEquals("string", parameter.path("schema").path("type").asText());
            assertEquals("date-time", parameter.path("schema").path("format").asText());
        }
    }

    private long createOrder() throws Exception {
        String response = mvc.perform(post("/api/v1/me/orders").header("Idempotency-Key", com.example.demo.TestOrders.key()).header("Authorization", "Bearer " + jwt.generateToken("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").longValue();
    }

    private org.springframework.test.web.servlet.ResultActions command(long id, String command) throws Exception {
        return mvc.perform(post(ADMIN + "/" + id + "/" + command).header("Authorization", adminToken()));
    }

    private void assertSales(MockHttpServletRequestBuilder request, long amount, long count) throws Exception {
        mvc.perform(request.header("Authorization", adminToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(amount)).andExpect(jsonPath("$.totalOrders").value(count));
    }

    private String adminToken() {
        return "Bearer " + jwt.generateToken("admin");
    }
}
