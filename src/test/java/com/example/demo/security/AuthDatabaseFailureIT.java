package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=500"})
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AuthDatabaseFailureIT {
    private static final String SECRET = randomValue();
    private static final String PASSWORD = randomValue();
    private static final String USERNAME = "auth-db-outage-test-user";
    private static final String SOURCE = "198.51.100.121";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2")
            .withDatabaseName("auth_db_outage").withUsername("inventory_app").withPassword(randomValue())
            .withUrlParam("connectTimeout", "1000").withUrlParam("socketTimeout", "1000")
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("app.login.rate-limit.trusted-proxies", () -> "127.0.0.1");
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DataSource dataSource;
    @Autowired LoginRateLimiter loginLimiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String hash;

    @BeforeEach
    void seed() {
        users.deleteAll();
        hash = encoder.encode(PASSWORD);
        users.saveAndFlush(User.builder().username(USERNAME).password(hash).role("ROLE_USER").build());
    }

    @Test
    void pausedDatabaseReturnsServerFailureAndSameJwtRecovers(CapturedOutput output) throws Exception {
        var initialLogin = login(PASSWORD);
        assertEquals(200, initialLogin.statusCode());
        String token = json.readTree(initialLogin.body()).path("token").asText();
        assertFalse(token.isBlank());
        assertEquals(200, get("/api/v1/me/orders", token).statusCode());
        assertProblem(login("incorrect-password"), 401, "INVALID_CREDENTIALS", "/auth/login");
        assertProblem(get("/api/v1/admin/orders", token), 403, "ACCESS_DENIED", "/api/v1/admin/orders");
        HttpResponse<String> failedOrders;
        HttpResponse<String> failedLogin;
        var docker = database.getDockerClient();
        boolean paused = false;
        try {
            // Only this newly-created Testcontainers DB; never an existing container/volume.
            docker.pauseContainerCmd(database.getContainerId()).exec();
            paused = true;
            evict();
            failedOrders = get("/api/v1/me/orders", token);
            failedLogin = login(PASSWORD);
            assertEquals(503, get("/readyz", null).statusCode());
            assertEquals(200, get("/livez", null).statusCode());
            assertProblem(get("/api/v1/me/orders", null), 401, "UNAUTHENTICATED", "/api/v1/me/orders");
            assertProblem(get("/api/v1/me/orders", "malformed-token"), 401, "UNAUTHENTICATED", "/api/v1/me/orders");
            assertEquals(0, loginLimiter.inFlightCount());
        } finally {
            if (paused) docker.unpauseContainerCmd(database.getContainerId()).exec();
            evict();
        }
        awaitReadiness();
        assertEquals(200, get("/api/v1/me/orders", token).statusCode());
        assertEquals(200, login(PASSWORD).statusCode());
        assertEquals(1, users.count());
        assertTrue(hash.equals(users.findByUsername(USERNAME).orElseThrow().getPassword()), "Stored hash must remain unchanged");
        System.out.printf("AUD-10 physical DB pause: orders=%d login=%d recovery=200 sameJwt=true%n",
                failedOrders.statusCode(), failedLogin.statusCode());
        assertProblem(failedOrders, 503, "SERVICE_UNAVAILABLE", "/api/v1/me/orders");
        assertProblem(failedLogin, 503, "SERVICE_UNAVAILABLE", "/auth/login");
        assertEquals(json.readTree(failedOrders.body()).path("detail"), json.readTree(failedLogin.body()).path("detail"));
        assertTrue(output.getAll().contains("system.database_unavailable"));
        for (String secret : List.of(SECRET, PASSWORD, hash, token, USERNAME, SOURCE,
                database.getPassword(), database.getJdbcUrl())) {
            assertFalse(output.getAll().contains(secret), "Sensitive value in production console");
            assertFalse(failedOrders.body().contains(secret), "Sensitive value in response");
            assertFalse(failedLogin.body().contains(secret), "Sensitive value in response");
        }
    }

    private HttpResponse<String> login(String password) throws Exception {
        return http.send(request("/auth/login").header("Content-Type", "application/json")
                .header("X-Forwarded-For", SOURCE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", USERNAME, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = request(path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code, String path) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        var body = json.readTree(response.body());
        assertEquals(6, body.size());
        assertEquals(status, body.path("status").asInt());
        assertEquals(code, body.path("code").asText());
        assertEquals(path, body.path("instance").asText());
        assertEquals("https://inventory-management-system.example/problems/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                body.path("type").asText());
        assertFalse(body.path("detail").asText().isBlank());
        assertFalse(response.headers().firstValue("X-Request-Id").orElseThrow().isBlank());
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());
    }

    private void evict() { ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections(); }

    private void awaitReadiness() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            if (get("/readyz", null).statusCode() == 200) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Disposable DB did not recover within bound");
    }

    private static String randomValue() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
}
