package com.example.demo.controller;

import com.example.demo.repository.OrderItemRepository;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.demo.CsvAssertions.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OrderCsvExportIT {
    private static final String PATH = "/api/v1/admin/orders/export";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("csv_export").withUsername("inventory_app");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired OrderService service;
    @Autowired DataSource dataSource;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean OrderItemRepository items;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private long itemId;

    @BeforeEach void fixtures() {
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','test-hash','ROLE_USER'),(2,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock) VALUES(1,'current-master',999,10)");
        itemId = 0;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach void cleanup() {
        reset(items);
        SecurityContextHolder.clearContext();
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users")) jdbc.update("DELETE FROM " + table);
    }

    private void order(long id, String status, List<String> names) {
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at,completed_at) VALUES(?,1,?,?,NOW(6),NOW(6),?)",
                id, names.size() * 200L, status, status.equals("COMPLETED") ? java.sql.Timestamp.valueOf("2026-10-01 00:00:00") : null);
        List<Object[]> values = new ArrayList<>();
        for (String name : names) values.add(new Object[]{++itemId, id, name});
        jdbc.batchUpdate("INSERT INTO order_items(id,order_id,product_id,product_name_snapshot,quantity,price) VALUES(?,?,1,?,2,100)", values);
    }

    private HttpResponse<byte[]> request(String username) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).timeout(Duration.ofSeconds(20));
        if (username != null) request.header("Authorization", "Bearer " + jwt.generateToken(username));
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String utf8(byte[] bytes) throws Exception {
        assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    @Test void adminEmptyExportHasOnlyHeaderAndCompatibleHttpContract() throws Exception {
        var response = request("admin");
        assertEquals(200, response.statusCode());
        assertEquals("attachment; filename=orders.csv", response.headers().firstValue("Content-Disposition").orElseThrow());
        assertEquals("text/csv;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(1, parse(utf8(response.body())).size());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
    }

    @Test void userAndAnonymousAreRejectedByRealSecurityBeforeCsvQuery() throws Exception {
        for (String username : new String[]{"alice", null}) {
            var response = request(username);
            assertEquals(username == null ? 401 : 403, response.statusCode());
            var body = new ObjectMapper().readTree(response.body());
            assertEquals(username == null ? "UNAUTHENTICATED" : "ACCESS_DENIED", body.get("code").asText());
        }
        verify(items, never()).findCsvBatch(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "日本語の商品🍎", "comma,name", "quote\"name", "line\nname", "line\r\nname", "line\rname"})
    void realDatabaseSingleRowParserRoundtripAndSnapshotPrices(String name) throws Exception {
        order(1, "PENDING", List.of(name));
        var before = snapshot();
        var rows = parse(utf8(request("admin").body()));
        assertEquals(List.of("1", name, "2", "100", "200", "PENDING"), rows.get(1));
        assertEquals(2, rows.size());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "  =1", "\t=1", "\r\n@x", "\u0001+1", "\u00a0-1", "\u200b=1", "＝1"})
    void formulaAndLeadingControlsAreNeutralizedOnlyInExport(String name) throws Exception {
        order(1, "PENDING", List.of(name));
        var before = snapshot();
        assertEquals("'\t" + name, parse(utf8(request("admin").body())).get(1).get(1));
        assertEquals(name, jdbc.queryForObject("SELECT product_name_snapshot FROM order_items", String.class));
        assertEquals(before, snapshot());
    }

    @Test void differentOrdersAndItemsAreSortedByBothIdsRegardlessOfInsertOrderAndStatus() throws Exception {
        order(20, "CANCELLED", List.of("later-first", "later-second"));
        order(10, "COMPLETED", List.of("earlier-first", "earlier-second"));
        var rows = parse(utf8(request("admin").body()));
        assertEquals(List.of("earlier-first", "earlier-second", "later-first", "later-second"),
                rows.subList(1, rows.size()).stream().map(row -> row.get(1)).toList());
        assertEquals("COMPLETED", rows.get(1).get(5));
        assertEquals("CANCELLED", rows.get(3).get(5));
        assertEquals(rows, parse(utf8(request("admin").body())));
    }

    @Test void manyRowsAcrossOrderAndBatchBoundariesUseThreeQueriesAndZeroManagedEntities() throws Exception {
        order(1, "PENDING", java.util.stream.IntStream.range(0, 601).mapToObj(i -> "first-" + i).toList());
        order(2, "SHIPPED", java.util.stream.IntStream.range(0, 502).mapToObj(i -> "second-" + i).toList());
        var before = snapshot();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Connection connection = DataSourceUtils.getConnection(dataSource);
            assertTrue(connection.isReadOnly());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
            var result = mockingDetails(items).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            assertEquals(0, entityManager.unwrap(Session.class).getStatistics().getEntityCount());
            return result;
        }).when(items).findCsvBatch(anyLong(), anyLong(), anyInt());
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var output = new ByteArrayOutputStream();
        service.exportCsv(output);
        assertEquals(3, stats.getPrepareStatementCount());
        assertEquals(0, stats.getEntityLoadCount());
        assertEquals(0, stats.getEntityInsertCount());
        assertEquals(0, stats.getEntityUpdateCount());
        assertEquals(0, stats.getEntityDeleteCount());
        var rows = parse(utf8(output.toByteArray()));
        assertEquals(1104, rows.size());
        assertEquals(1103, rows.subList(1, rows.size()).stream().map(row -> row.get(1)).distinct().count());
        for (int i = 0; i < 601; i++) assertEquals("first-" + i, rows.get(i + 1).get(1));
        for (int i = 0; i < 502; i++) assertEquals("second-" + i, rows.get(i + 602).get(1));
        assertEquals(before, snapshot());
        assertReleased();
    }

    @Test void repeatableReadExcludesConcurrentNewOrdersAndKeepsOldStatusAcrossBatches() throws Exception {
        order(1, "PENDING", java.util.stream.IntStream.range(0, 501).mapToObj(i -> "item-" + i).toList());
        var worker = Executors.newSingleThreadExecutor();
        var output = new ByteArrayOutputStream() {
            boolean changed;
            @Override public void flush() throws IOException {
                if (!changed) {
                    changed = true;
                    try {
                        worker.submit(() -> {
                            jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id=1");
                            order(2, "PENDING", List.of("concurrent-new"));
                        }).get(10, TimeUnit.SECONDS);
                    } catch (Exception ex) { throw new IOException("test concurrent update failed", ex); }
                }
            }
        };
        try { service.exportCsv(output); } finally { worker.shutdownNow(); }
        var rows = parse(utf8(output.toByteArray()));
        assertEquals(502, rows.size());
        assertTrue(rows.subList(1, rows.size()).stream().allMatch(row -> row.get(0).equals("1") && row.get(5).equals("PENDING")));
        assertEquals("SHIPPED", jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertReleased();
    }

    @Test void writeDisconnectRollsBackReadTransactionReleasesConnectionAndStopsQueries() {
        order(1, "PENDING", java.util.stream.IntStream.range(0, 501).mapToObj(i -> "item-" + i).toList());
        var before = snapshot();
        assertThrows(IOException.class, () -> service.exportCsv(new OutputStream() {
            public void write(int b) throws IOException { throw new IOException("simulated client disconnect"); }
        }));
        verify(items, times(1)).findCsvBatch(anyLong(), anyLong(), anyInt());
        assertReleased();
        assertEquals(before, snapshot());
    }

    @Test void earlyDatabaseFailureIsSafeProblemDetailAndActualProductionLogsDoNotLeak(CapturedOutput logs) throws Exception {
        int start = logs.getAll().length();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private database diagnostic",
                new java.sql.SQLException("SELECT csv-private-value alice uk_users_username")))
                .when(items).findCsvBatch(anyLong(), anyLong(), anyInt());
        mvc.perform(get(PATH).header("Authorization", "Bearer " + jwt.generateToken("admin")))
                .andExpect(status().isInternalServerError()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
        assertSafeLogs(logs.getAll().substring(start));
        assertTrue(logs.getAll().substring(start).contains("java.sql.SQLException"));
        assertReleased();
    }

    @Test void successfulExportDoesNotLogCsvCellsOrCredentials(CapturedOutput logs) throws Exception {
        order(1, "PENDING", List.of("csv-private-value"));
        int start = logs.getAll().length();
        assertEquals("csv-private-value", parse(utf8(request("admin").body())).get(1).get(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> logs.getAll().substring(start).contains("http.request.completed"));
        String output = logs.getAll().substring(start);
        assertFalse(output.contains("csv-private-value"));
        assertFalse(output.contains(SECRET));
        assertFalse(output.contains("Bearer "));
    }

    @Test void midStreamDatabaseFailureAbortsRealHttpWithoutJsonAndReleasesConnection(CapturedOutput logs) throws Exception {
        order(1, "PENDING", java.util.stream.IntStream.range(0, 501).mapToObj(i -> "csv-private-value-" + i).toList());
        var before = snapshot();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("SELECT csv-private-value idempotency-secret-key");
            return mockingDetails(items).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(items).findCsvBatch(anyLong(), anyLong(), anyInt());
        int start = logs.getAll().length();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer " + jwt.generateToken("admin")).timeout(Duration.ofSeconds(20)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode()); // headers are already committed; body must terminate abnormally.
        try (var input = response.body()) { assertThrows(IOException.class, input::readAllBytes); }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(this::assertReleased);
        assertSafeLogs(logs.getAll().substring(start));
        assertEquals(before, snapshot());
        assertEquals(2, calls.get());
    }

    @Test void existingForeignKeyIndexSupportsOrderedRangeWithoutMigration() throws Exception {
        order(1, "PENDING", java.util.stream.IntStream.range(0, 601).mapToObj(i -> "item-" + i).toList());
        order(2, "SHIPPED", java.util.stream.IntStream.range(0, 502).mapToObj(i -> "item-" + i).toList());
        var index = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_items' AND INDEX_NAME='idx_order_items_order' ORDER BY SEQ_IN_INDEX", String.class);
        assertEquals(List.of("order_id"), index);
        String sql = OrderItemRepository.class.getMethod("findCsvBatch", long.class, long.class, int.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        var plan = jdbc.queryForList("EXPLAIN " + sql.replace(":orderId", "1").replace(":itemId", "500").replace(":limit", "500"));
        var range = plan.stream().filter(row -> "DERIVED".equals(row.get("select_type"))).findFirst().orElseThrow();
        assertEquals("idx_order_items_order", range.get("key"), plan.toString());
        assertEquals("range", range.get("type"), plan.toString());
        assertEquals("16", String.valueOf(range.get("key_len")), plan.toString());
        assertFalse(String.valueOf(range.get("Extra")).contains("filesort"), plan.toString());
        assertTrue(plan.stream().filter(row -> String.valueOf(row.get("table")).startsWith("<derived"))
                .allMatch(row -> Long.parseLong(String.valueOf(row.get("rows"))) <= 500), plan.toString());
    }

    private List<?> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM orders ORDER BY id"), jdbc.queryForList("SELECT * FROM order_items ORDER BY id"),
                jdbc.queryForList("SELECT * FROM products ORDER BY id"), jdbc.queryForList("SELECT * FROM inventory_movements ORDER BY id"));
    }

    private void assertReleased() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(0, ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());
    }

    private void assertSafeLogs(String output) {
        assertTrue(output.contains("system.unhandled_error"));
        for (String secret : List.of("csv-private-value", "idempotency-secret-key", "alice", "uk_users_username", "SELECT csv")) assertFalse(output.contains(secret), secret);
        assertFalse(output.contains(SECRET));
    }
}
