package com.example.demo.controller;

import com.example.demo.repository.OrderItemRepository;
import com.example.demo.security.JwtUtil;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import javax.sql.DataSource;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.demo.CsvAssertions.parse;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Issue #156: the production 10s transaction deadline must not truncate a CSV whose HTTP transfer is slow.
 * The prod profile is used unchanged, so the transaction deadline under test is the real 10 seconds.
 * A raw socket with a 4 KiB receive buffer and a body larger than the loopback socket buffers forces
 * real TCP backpressure; sleeps only need to exceed the deadline, never to win a race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.order-csv.max-bytes=12582912") // 12 MiB: the 10 MB slow-client body fits, the 14 MB body does not.
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCsvExportDeadlineIT {
    private static final String PATH = "/api/v1/admin/orders/export";
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    // Each row is ~130 UTF-8 bytes. 80,000 rows (~10 MB) exceed client+server loopback socket buffers.
    private static final int SLOW_CLIENT_ROWS = 80_000;
    private static final int OVERSIZED_ROWS = 110_000;
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("csv_deadline").withUsername("inventory_app");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("app.login.rate-limit.trusted-proxies", () -> "127.0.0.1");
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired DataSource dataSource;
    @Value("${spring.transaction.default-timeout}") Duration transactionTimeout;
    @MockitoSpyBean OrderItemRepository items;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void fixtures() {
        jdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES(UUID_v4(),1,'alice','test-hash','ROLE_USER'),(UUID_v4(),2,'admin','test-hash','ROLE_ADMIN')");
        jdbc.update("INSERT INTO products(id,name,price,stock) VALUES(1,'current-master',999,10)");
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at) VALUES(1,1,100,'PENDING',NOW(6),NOW(6))");
    }

    @AfterEach void cleanup() {
        reset(items);
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users")) jdbc.update("DELETE FROM " + table);
    }

    private void items(int count) {
        // MariaDB's built-in SEQUENCE engine keeps fixture creation to one statement.
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,product_name_snapshot,quantity,price) "
                + "SELECT seq,1,1,CONCAT('item-',seq,'-',REPEAT('x',100)),2,100 FROM seq_1_to_" + count);
    }

    private String token() { return "Bearer " + com.example.demo.TestAuth.token(jwt, jdbc, "admin"); }

    private int activeConnections() { return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections(); }

    private static long spoolFiles() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("order-csv-")).count();
        }
    }

    private HttpResponse<byte[]> request() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH)).header("Authorization", token())
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** HTTP/1.1 over a socket whose tiny receive window makes the server block on write until the test reads. */
    private final class SlowClient implements AutoCloseable {
        final Socket socket = new Socket();
        final InputStream input;
        final int status;
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        SlowClient() throws IOException {
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(60_000);
            socket.getOutputStream().write(("GET " + PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: " + token()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            input = new BufferedInputStream(socket.getInputStream(), 1024);
            String[] lines = head().split("\r\n");
            status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
            }
        }

        private String head() throws IOException {
            var head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = input.read();
                if (next < 0) throw new IOException("connection closed before headers");
                head.write(next);
                matched = (next == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (next == '\r' ? 1 : 0);
            }
            return head.toString(StandardCharsets.US_ASCII);
        }

        byte[] body() throws IOException {
            String length = headers.get("Content-Length");
            assertNotNull(length, "a spooled export declares Content-Length so clients can detect truncation: " + headers);
            return input.readNBytes(Integer.parseInt(length));
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    @Test void productionProfileKeepsTheTenSecondBusinessTransactionDeadline() {
        assertEquals(Duration.ofSeconds(10), transactionTimeout);
    }

    @Test void slowClientLongerThanTransactionDeadlineReceivesCompleteCsvWithoutHoldingDatabaseConnection() throws Exception {
        items(SLOW_CLIENT_ROWS);
        try (var client = new SlowClient()) {
            assertEquals(200, client.status);
            assertEquals("attachment; filename=orders.csv", client.headers.get("Content-Disposition"));
            assertEquals("text/csv;charset=UTF-8", client.headers.get("Content-Type"));
            // The response is now blocked by the client. No DB transaction/connection may be held while sending.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(0, activeConnections(),
                    "CSV transfer to a slow client must not hold a DB connection/transaction"));
            assertEquals(1, spoolFiles(), "the bounded snapshot spool exists only while the transfer is in progress");
            Thread.sleep(transactionTimeout.plusMillis(500).toMillis()); // Exceed the real deadline while the client stalls.
            var rows = parse(new String(client.body(), StandardCharsets.UTF_8));
            assertEquals(SLOW_CLIENT_ROWS + 1, rows.size());
            assertEquals(List.of("1", "item-1-" + "x".repeat(100), "2", "100", "200", "PENDING"), rows.get(1));
            assertEquals("item-" + SLOW_CLIENT_ROWS + "-" + "x".repeat(100), rows.get(SLOW_CLIENT_ROWS).get(1));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> spoolFiles() == 0);
        assertEquals(0, activeConnections());
    }

    @Test void snapshotIsFixedBeforeTransferSoChangesDuringSlowTransferAreNotMixedIn() throws Exception {
        items(SLOW_CLIENT_ROWS);
        try (var client = new SlowClient()) {
            assertEquals(200, client.status);
            jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id=1");
            jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at) VALUES(2,1,100,'PENDING',NOW(6),NOW(6))");
            jdbc.update("INSERT INTO order_items(id,order_id,product_id,product_name_snapshot,quantity,price) VALUES(999999,2,1,'late',1,100)");
            var rows = parse(new String(client.body(), StandardCharsets.UTF_8));
            assertEquals(SLOW_CLIENT_ROWS + 1, rows.size());
            assertTrue(rows.subList(1, rows.size()).stream().allMatch(row -> row.get(0).equals("1") && row.get(5).equals("PENDING")));
        }
    }

    @Test void secondExportWhileFirstIsTransferringIsRejectedBeforeAnyQueryAndRetrySucceedsAfterRelease() throws Exception {
        items(SLOW_CLIENT_ROWS);
        try (var first = new SlowClient()) {
            assertEquals(200, first.status);
            int queriesBefore = mockingDetails(items).getInvocations().size();
            var busy = request();
            assertEquals(503, busy.statusCode());
            assertEquals("application/problem+json", busy.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(busy.headers().firstValue("Content-Disposition").isEmpty());
            var problem = new tools.jackson.databind.json.JsonMapper().readTree(busy.body());
            assertEquals("ORDER_CSV_EXPORT_BUSY", problem.get("code").asText());
            assertEquals(queriesBefore, mockingDetails(items).getInvocations().size(), "a rejected export must not query the DB");
            assertEquals(SLOW_CLIENT_ROWS + 1, parse(new String(first.body(), StandardCharsets.UTF_8)).size());
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var retry = request();
            assertEquals(200, retry.statusCode());
            assertEquals(SLOW_CLIENT_ROWS + 1, parse(new String(retry.body(), StandardCharsets.UTF_8)).size());
        });
    }

    @Test void clientDisconnectDuringTransferReleasesSpoolPermitAndConnection() throws Exception {
        items(SLOW_CLIENT_ROWS);
        try (var client = new SlowClient()) {
            assertEquals(200, client.status);
            assertEquals(1024, client.input.readNBytes(1024).length);
        } // Abort mid-body.
        await().atMost(Duration.ofSeconds(30)).until(() -> spoolFiles() == 0);
        assertEquals(0, activeConnections());
        var retry = request();
        assertEquals(200, retry.statusCode());
        assertEquals(SLOW_CLIENT_ROWS + 1, parse(new String(retry.body(), StandardCharsets.UTF_8)).size());
    }

    @Test void oversizedExportFailsBeforeAnyCsvByteAndRetryIsDeterministic() throws Exception {
        items(OVERSIZED_ROWS);
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = request();
            assertEquals(503, response.statusCode());
            assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
            var problem = new tools.jackson.databind.json.JsonMapper().readTree(response.body());
            assertEquals("ORDER_CSV_EXPORT_LIMIT_EXCEEDED", problem.get("code").asText());
            assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("item-"));
            assertEquals(0, spoolFiles());
            assertEquals(0, activeConnections());
        }
    }

    @Test void transactionDeadlineDuringSnapshotReadFailsBeforeAnyCsvByte() throws Exception {
        items(1001);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            // Only exceeding the deadline matters: the next query is created after it has passed.
            if (calls.incrementAndGet() == 2) Thread.sleep(transactionTimeout.plusMillis(500).toMillis());
            return mockingDetails(items).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(items).findCsvBatch(anyLong(), anyLong(), anyInt());
        var response = request();
        assertEquals(503, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
        var problem = new tools.jackson.databind.json.JsonMapper().readTree(response.body());
        assertEquals("ORDER_CSV_EXPORT_LIMIT_EXCEEDED", problem.get("code").asText());
        assertEquals(2, calls.get());
        assertEquals(0, spoolFiles());
        assertEquals(0, activeConnections());
    }
}
