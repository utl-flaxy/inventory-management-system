package com.example.demo.controller;

import com.example.demo.DemoApplication;
import com.example.demo.dto.OrderResponse;
import com.example.demo.repository.OrderRepository;
import com.example.demo.service.OrderService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.hibernate.Hibernate;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mariadb.MariaDBContainer;

import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static com.example.demo.CsvAssertions.parse;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, real profiles and MariaDB: no test override of spring.jpa.open-in-view. */
@Testcontainers
class OsivApiIT {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String PASSWORD = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2");
    private final ObjectMapper json = new tools.jackson.databind.json.JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String ADMIN = "/api/v1/admin/orders";
    private static final String ME = "/api/v1/me/orders";
    private Probe probe;
    private String base;
    private Trace last;

    @ParameterizedTest(name = "OSIV API boundary: {0}")
    @ValueSource(strings = {"default", "local", "prod"})
    void allApisMaterializeResponsesAndPreserveTransactionsWithoutOsiv(String profile) throws Exception {
        try (var context = new SpringApplicationBuilder(DemoApplication.class, ProbeConfiguration.class).run(
                "--server.port=0", "--app.login.rate-limit.trusted-proxies=127.0.0.1",
                "--spring.profiles.active=" + (profile.equals("default") ? "" : profile),
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(), "--JWT_SECRET=" + SECRET)) {
            assertEquals("false", context.getEnvironment().getProperty("spring.jpa.open-in-view"));
            assertTrue(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenEntityManagerInViewFilter.class).isEmpty());
            base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            probe = context.getBean(Probe.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users"))
                jdbc.update("DELETE FROM " + table);
            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));

            String adminName = "osiv-admin-" + profile;
            String aliceName = "osiv-alice-" + profile;
            String bobName = "osiv-bob-" + profile;
            for (String username : List.of(adminName, aliceName, bobName)) {
                var registered = call("POST", "/auth/register", Map.of("username", username, "password", PASSWORD), null, null, 200);
                assertEquals(Set.of("id", "username", "role"), fields(registered));
            }
            jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username=?", adminName);
            String admin = login(adminName);
            String alice = login(aliceName);
            String bob = login(bobName);
            var unknown = call("POST", "/auth/login", Map.of("username", "osiv-missing", "password", PASSWORD), null, null, 401);
            var wrong = call("POST", "/auth/login", Map.of("username", aliceName, "password", "wrong-password"), null, null, 401);
            ((ObjectNode) unknown).remove("requestId");
            ((ObjectNode) wrong).remove("requestId");
            assertEquals(unknown, wrong);

            var first = call("POST", "/products", Map.of("name", "osiv 日本語,保存名", "price", 100, "stock", 10), admin, null, 200);
            assertEquals(Set.of("id", "name", "price", "stock", "version"), fields(first));
            assertWritable("insert into products", null);
            long productId = first.path("id").asLong();
            var second = call("POST", "/products", Map.of("name", "osiv second", "price", 200, "stock", 10), admin, null, 200);
            long secondId = second.path("id").asLong();
            var disposable = call("POST", "/products", Map.of("name", "osiv disposable", "price", 50, "stock", 1), admin, null, 200);
            call("DELETE", "/products/" + disposable.path("id").asLong(), null, admin, null, 200);
            assertWritable("delete from products", Connection.TRANSACTION_READ_COMMITTED);

            var products = call("GET", "/products?size=1", null, alice, null, 200);
            assertPage(products);
            assertReadOnly("from products", 2, null);
            assertEquals(100, call("GET", "/products?size=500", null, alice, null, 200).path("size").asInt());
            call("GET", "/products/" + productId, null, alice, null, 200);
            assertReadOnly("from products", 1, null);
            assertPage(call("GET", "/products/search?keyword=osiv&size=1", null, alice, null, 200));
            assertReadOnly("from products", 2, null);

            Map<String, Object> body = Map.of("items", List.of(Map.of("productId", productId, "quantity", 2),
                    Map.of("productId", secondId, "quantity", 1)));
            String orderKey = UUID.randomUUID().toString();
            var order = call("POST", ME, body, alice, orderKey, 200);
            assertOrder(order, aliceName, 400L);
            assertWritable("insert into orders", Connection.TRANSACTION_READ_COMMITTED);
            long orderId = order.path("id").asLong();
            assertEquals(orderId, call("POST", ME, body, alice, orderKey, 200).path("id").asLong());
            assertEquals(8, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
            call("GET", ME + "/" + orderId, null, bob, null, 404);
            call("GET", ADMIN + "/" + orderId, null, alice, null, 403);
            call("GET", ME, null, null, null, 401);
            call("GET", ADMIN, null, null, null, 401);
            call("GET", "/products", null, null, null, 401);
            call("POST", "/products", Map.of("name", "denied", "price", 10, "stock", 1), alice, null, 403);

            var updated = call("PUT", "/products/" + productId,
                    Map.of("name", "renamed", "price", 900, "version", 1), admin, null, 200);
            assertEquals(2, updated.path("version").asLong());
            assertWritable("update products", Connection.TRANSACTION_READ_COMMITTED);
            for (String path : List.of(ADMIN + "/" + orderId, ME + "/" + orderId)) {
                var detail = call("GET", path, null, path.startsWith(ADMIN) ? admin : alice, null, 200);
                assertOrder(detail, aliceName, 400L);
                assertEquals("osiv 日本語,保存名", detail.path("items").get(0).path("productName").asText());
                assertEquals(100, detail.path("items").get(0).path("unitPrice").asLong());
                assertReadOnly("from orders", 1, null);
            }

            // Genuine LAZY associations exist; the API does not serialize a detached Entity.
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.setReadOnly(true);
            var detached = tx.execute(status -> context.getBean(OrderRepository.class).findById(orderId).orElseThrow());
            assertFalse(Hibernate.isInitialized(detached.getCustomer()));
            assertFalse(context.getBean(EntityManagerFactory.class).getPersistenceUnitUtil().isLoaded(detached, "orderItems"));
            OrderResponse dto = context.getBean(OrderService.class).findByIdResponse(orderId);
            assertEquals(2, json.readTree(context.getBean(ObjectMapper.class).writeValueAsString(dto)).path("items").size());
            assertFalse(TransactionSynchronizationManager.hasResource(context.getBean(EntityManagerFactory.class)));

            // Extra orders make page content + count + bounded DTO-row query mandatory.
            var cancel = call("POST", ME, body, alice, UUID.randomUUID().toString(), 200);
            var adminCancel = call("POST", ME, body, alice, UUID.randomUUID().toString(), 200);
            var statusCancel = call("POST", ME, body, alice, UUID.randomUUID().toString(), 200);
            for (String path : List.of(ADMIN, ME)) {
                var page = call("GET", path + "?size=1", null, path.equals(ADMIN) ? admin : alice, null, 200);
                assertPage(page);
                assertOrder(page.path("content").get(0), aliceName, 2000L);
                assertReadOnly("from orders", 3, Connection.TRANSACTION_REPEATABLE_READ);
            }
            var shipped = call("POST", ADMIN + "/" + orderId + "/ship", null, admin, null, 200);
            assertEquals("SHIPPED", shipped.path("status").asText());
            assertEquals(1, shipped.path("version").asLong());
            assertEquals(jdbc.queryForObject("SELECT version FROM orders WHERE id=?", Long.class, orderId), shipped.path("version").asLong());
            assertWritable("update orders", Connection.TRANSACTION_READ_COMMITTED);
            var completed = call("POST", ADMIN + "/" + orderId + "/complete", null, admin, null, 200);
            assertEquals("COMPLETED", completed.path("status").asText());
            assertEquals(2, completed.path("version").asLong());
            assertFalse(completed.path("completedAt").isNull());
            var persisted = call("GET", ADMIN + "/" + orderId, null, admin, null, 200);
            assertEquals(Instant.parse(completed.path("updatedAt").asText()).truncatedTo(ChronoUnit.MICROS),
                    Instant.parse(persisted.path("updatedAt").asText()));
            ((ObjectNode) completed).remove("updatedAt");
            ((ObjectNode) persisted).remove("updatedAt");
            assertEquals(completed, persisted);
            var cancelled = call("POST", ME + "/" + cancel.path("id").asLong() + "/cancel", null, alice, null, 200);
            assertEquals("CANCELLED", cancelled.path("status").asText());
            assertEquals(1, cancelled.path("version").asLong());
            assertWritable("update orders", Connection.TRANSACTION_READ_COMMITTED);
            call("POST", ME + "/" + cancel.path("id").asLong() + "/cancel", null, alice, null, 409);
            call("POST", ADMIN + "/" + adminCancel.path("id").asLong() + "/cancel", null, admin, null, 200);
            assertWritable("update orders", Connection.TRANSACTION_READ_COMMITTED);
            call("PATCH", ADMIN + "/" + statusCancel.path("id").asLong() + "/status", Map.of("status", "CANCELLED"), admin, null, 200);
            assertWritable("update orders", Connection.TRANSACTION_READ_COMMITTED);
            assertEquals(8, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
            var sales = call("GET", ADMIN + "/sales", null, admin, null, 200);
            assertEquals(Set.of("totalSales", "totalOrders"), fields(sales));
            assertEquals(400, sales.path("totalSales").asLong());
            assertEquals(1, sales.path("totalOrders").asLong());
            assertReadOnly("from orders", 1, null);

            String inventory = "/api/v1/admin/products/" + productId;
            String inventoryKey = UUID.randomUUID().toString();
            var adjustment = call("POST", inventory + "/inventory-adjustments", Map.of("delta", 3, "reason", "RECEIPT"), admin, inventoryKey, 200);
            assertEquals(Set.of("id", "username"), fields(adjustment.path("actor")));
            assertWritable("update products", Connection.TRANSACTION_READ_COMMITTED);
            var replay = call("POST", inventory + "/inventory-adjustments", Map.of("delta", 3, "reason", "RECEIPT"), admin, inventoryKey, 200);
            assertEquals(Instant.parse(adjustment.path("createdAt").asText()).truncatedTo(ChronoUnit.MICROS),
                    Instant.parse(replay.path("createdAt").asText()));
            ((ObjectNode) adjustment).remove("createdAt");
            ((ObjectNode) replay).remove("createdAt");
            assertEquals(adjustment, replay);
            call("POST", inventory + "/inventory-adjustments", Map.of("delta", 4, "reason", "RECEIPT"), admin, inventoryKey, 409);
            assertEquals(11, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
            var history = call("GET", inventory + "/inventory-movements?size=1", null, admin, null, 200);
            assertPage(history);
            assertEquals(Set.of("id", "username"), fields(history.path("content").get(0).path("actor")));
            assertReadOnly("from inventory_movements", 2, null);

            var csv = send("GET", ADMIN + "/export", null, admin, null);
            assertEquals(200, csv.statusCode());
            assertEquals("attachment; filename=orders.csv", csv.headers().firstValue("Content-Disposition").orElseThrow());
            assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().contains("text/csv"));
            assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().toLowerCase(Locale.ROOT).contains("utf-8"));
            var rows = parse(csv.body());
            assertEquals(9, rows.size());
            assertEquals("osiv 日本語,保存名", rows.get(1).get(1));
            assertEquals("100", rows.get(1).get(3));
            assertReadOnly("from order_items", 1, Connection.TRANSACTION_REPEATABLE_READ);
            assertFalse(last.entityManagerAtEnd);
            call("GET", ADMIN + "/export", null, alice, null, 403);
            call("GET", ADMIN + "/export", null, null, null, 401);
            assertEquals(11, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, productId));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        }
    }

    private String login(String username) throws Exception {
        return call("POST", "/auth/login", Map.of("username", username, "password", PASSWORD), null, null, 200).path("token").asText();
    }

    private JsonNode call(String method, String path, Object body, String token, String key, int status) throws Exception {
        var response = send(method, path, body, token, key);
        assertEquals(status, response.statusCode(), method + " " + path);
        if (response.body().isEmpty()) return json.nullNode();
        if (status == 200) assertTrue(last.jsonWriting, "Observe the actual JSON converter boundary: " + path);
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object body, String token, String key) throws Exception {
        String id = UUID.randomUUID().toString();
        var completion = new CompletableFuture<Trace>();
        probe.pending.put(id, completion);
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("X-Osiv-Probe", id)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) request.header("Content-Type", "application/json").header("X-Forwarded-For", "198.51.100.1");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            last = completion.get(10, TimeUnit.SECONDS);
            assertFalse(last.transactionAtWrite);
            assertFalse(last.entityManagerAtWrite);
            assertFalse(last.entityManagerAtEnd);
            assertEquals(0, last.sqlDuringJsonWrite);
            return response;
        } finally {
            probe.pending.remove(id);
        }
    }

    private void assertReadOnly(String fragment, int count, Integer isolation) {
        var statements = last.sql.stream().filter(sql -> sql.text.contains(fragment)).toList();
        assertEquals(count, statements.size(), fragment);
        for (var statement : statements) {
            assertTrue(statement.transaction);
            assertTrue(statement.readOnly);
            assertEquals(isolation, statement.isolation);
        }
    }

    private void assertWritable(String fragment, Integer isolation) {
        var statements = last.sql.stream().filter(sql -> sql.text.contains(fragment)).toList();
        assertFalse(statements.isEmpty(), fragment);
        for (var statement : statements) {
            assertTrue(statement.transaction);
            assertFalse(statement.readOnly);
            assertEquals(isolation, statement.isolation);
        }
    }

    private void assertPage(JsonNode page) {
        assertEquals(Set.of("content", "page", "size", "totalElements", "totalPages", "first", "last"), fields(page));
    }

    private void assertOrder(JsonNode order, String username, long total) {
        assertEquals(Set.of("id", "status", "orderedAt", "updatedAt", "completedAt", "totalPrice", "version", "customer", "items"), fields(order));
        assertEquals(Set.of("id", "username"), fields(order.path("customer")));
        assertEquals(username, order.path("customer").path("username").asText());
        assertEquals(total, order.path("totalPrice").asLong());
        assertEquals(2, order.path("items").size());
        order.path("items").forEach(item -> assertEquals(Set.of("productId", "productName", "unitPrice", "quantity", "lineTotal"), fields(item)));
    }

    private Set<String> fields(JsonNode node) {
        var result = new HashSet<String>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    record Sql(String text, boolean transaction, boolean readOnly, Integer isolation) {}
    static class Trace {
        final List<Sql> sql = new ArrayList<>();
        boolean jsonWriting, transactionAtWrite, entityManagerAtWrite, entityManagerAtEnd;
        int sqlDuringJsonWrite;
    }
    static class Probe implements StatementInspector {
        final ThreadLocal<Trace> current = new ThreadLocal<>();
        final ConcurrentMap<String, CompletableFuture<Trace>> pending = new ConcurrentHashMap<>();
        @Override public String inspect(String sql) {
            var trace = current.get();
            if (trace != null) {
                trace.sql.add(new Sql(sql.toLowerCase(Locale.ROOT), TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(), TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));
                if (trace.jsonWriting) trace.sqlDuringJsonWrite++;
            }
            return sql;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean Probe probe() { return new Probe(); }
        @Bean HibernatePropertiesCustomizer sqlProbe(Probe probe) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", probe);
        }
        @Bean JsonBoundary jsonBoundary(Probe probe, EntityManagerFactory emf) { return new JsonBoundary(probe, emf); }
        @Bean FilterRegistrationBean<Filter> requestProbe(Probe probe, EntityManagerFactory emf) {
            var registration = new FilterRegistrationBean<Filter>();
            registration.setFilter((ServletRequest request, ServletResponse response, FilterChain chain) -> {
                String id = ((HttpServletRequest) request).getHeader("X-Osiv-Probe");
                var completion = id == null ? null : probe.pending.get(id);
                if (completion == null) { chain.doFilter(request, response); return; }
                var trace = new Trace();
                probe.current.set(trace);
                try { chain.doFilter(request, response); }
                finally {
                    trace.entityManagerAtEnd = TransactionSynchronizationManager.hasResource(emf);
                    probe.current.remove();
                    completion.complete(trace);
                }
            });
            registration.setOrder(Integer.MIN_VALUE);
            return registration;
        }
    }

    @RestControllerAdvice
    @org.springframework.boot.test.context.TestComponent
    static class JsonBoundary implements ResponseBodyAdvice<Object> {
        private final Probe probe;
        private final EntityManagerFactory emf;
        JsonBoundary(Probe probe, EntityManagerFactory emf) { this.probe = probe; this.emf = emf; }
        @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
        @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
                Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
            var trace = probe.current.get();
            if (trace != null) {
                trace.jsonWriting = true;
                trace.transactionAtWrite = TransactionSynchronizationManager.isActualTransactionActive();
                trace.entityManagerAtWrite = TransactionSynchronizationManager.hasResource(emf);
            }
            return body;
        }
    }
}
