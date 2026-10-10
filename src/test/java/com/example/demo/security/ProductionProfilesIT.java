package com.example.demo.security;

import com.example.demo.DemoApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ProductionProfilesIT {
    private static final String PASSWORD = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("production_profiles").withUsername("inventory_app").withPassword(PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private String base;

    @ParameterizedTest(name = "profile: {0}")
    @ValueSource(strings = {"prod", "local", "default"})
    void realProfilesExposeOnlyAllowedEndpointsWithSafeHealthAndHeaders(String profile, CapturedOutput output) throws Exception {
        try (var context = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--spring.profiles.active=" + (profile.equals("default") ? "" : profile),
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=inventory_app", "--spring.datasource.password=" + PASSWORD,
                "--spring.datasource.driver-class-name=" + database.getDriverClassName(),
                "--spring.jpa.open-in-view=false", "--JWT_SECRET=" + SECRET)) {
            base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var environment = context.getEnvironment();
            assertEquals("false", environment.getProperty("spring.jpa.show-sql"));
            assertEquals(profile.equals("local") ? "true" : "false",
                    environment.getProperty("spring.jpa.properties.hibernate.format_sql"));
            assertEquals("false", environment.getProperty("spring.h2.console.enabled"));
            assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
            assertEquals("never", environment.getProperty("management.endpoint.health.show-components"));
            assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

            var health = get("/actuator/health", null);
            assertEquals(200, health.statusCode());
            assertEquals(json.valueToTree(Map.of("status", "UP")), json.readTree(health.body()));
            assertEquals("DENY", health.headers().firstValue("X-Frame-Options").orElseThrow());
            assertEquals(List.of("health"), context.getBean(WebEndpointsSupplier.class).getEndpoints()
                    .stream().map(endpoint -> endpoint.getEndpointId().toString()).sorted().toList());

            boolean local = profile.equals("local");
            var api = get("/v3/api-docs", null);
            assertEquals(local ? 200 : 404, api.statusCode());
            assertEquals(local ? 302 : 404, get("/swagger-ui.html", null).statusCode());
            assertEquals(local ? 200 : 404, get("/swagger-ui/index.html", null).statusCode());
            assertEquals(local ? 200 : 404, get("/v3/api-docs/swagger-config", null).statusCode());
            boolean generatedDocs = context.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                    .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                    .flatMap(mapping -> mapping.getPatternValues().stream()).anyMatch(path -> path.startsWith("/v3/api-docs"));
            assertEquals(local, generatedDocs);

            for (String path : List.of("/h2-console", "/h2-console/", "/actuator", "/actuator/env",
                    "/actuator/configprops", "/actuator/beans", "/actuator/loggers", "/actuator/heapdump",
                    "/actuator/threaddump", "/actuator/metrics", "/actuator/mappings", "/actuator/health/db")) {
                var response = get(path, null);
                assertEquals(404, response.statusCode(), path);
                assertNoSecrets(response.body());
                assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
            }

            var unauthenticated = get("/products", null);
            assertEquals(401, unauthenticated.statusCode());
            assertEquals("UNAUTHENTICATED", json.readTree(unauthenticated.body()).path("code").asText());
            assertNoSecrets(unauthenticated.body());

            // Flyway V1〜V6と実APIのDMLを同じ専用userで実行できること。
            var jdbc = context.getBean(JdbcTemplate.class);
            assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("inventory_app@"));
            var grants = jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class);
            String databaseGrant = " ON `production\\_profiles`.* TO ";
            assertTrue(grants.stream().anyMatch(grant -> grant.contains(databaseGrant)));
            assertTrue(grants.stream().allMatch(grant -> grant.startsWith("GRANT USAGE ON *.* TO ")
                    || grant.contains(databaseGrant)));
            assertTrue(grants.stream().noneMatch(grant -> grant.contains("ALL PRIVILEGES") || grant.contains("GRANT OPTION")));
            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
            String username = "admin" + profile;
            assertEquals(200, post("/auth/register", Map.of("username", username, "password", PASSWORD), null).statusCode());
            jdbc.update("UPDATE users SET role='ROLE_ADMIN' WHERE username=?", username);
            var login = post("/auth/login", Map.of("username", username, "password", PASSWORD), null);
            assertEquals(200, login.statusCode());
            String token = json.readTree(login.body()).path("token").asText();
            assertFalse(token.isBlank());
            var product = post("/products", Map.of("name", "profile-product", "price", 100, "stock", 10), token);
            assertEquals(200, product.statusCode());
            long id = json.readTree(product.body()).path("id").longValue();
            assertEquals(200, get("/products", token).statusCode());
            assertEquals(200, delete("/products/" + id, token).statusCode());
            assertEquals(404, delete("/products/" + id, token).statusCode());
            var referenced = post("/products", Map.of("name", "audited-product", "price", 100, "stock", 10), token);
            assertEquals(200, referenced.statusCode());
            long referencedId = json.readTree(referenced.body()).path("id").longValue();
            assertEquals(200, post("/api/v1/admin/products/" + referencedId + "/inventory-adjustments",
                    Map.of("delta", 1, "reason", "RECEIPT"), token).statusCode());
            var conflict = delete("/products/" + referencedId, token);
            assertEquals(409, conflict.statusCode());
            assertEquals("PRODUCT_DELETE_CONFLICT", json.readTree(conflict.body()).path("code").asText());
            assertEquals("注文履歴または在庫履歴から参照されているため商品を削除できません",
                    json.readTree(conflict.body()).path("detail").asText());
            assertEquals(11, jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, referencedId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE product_id=?",
                    Integer.class, referencedId));
            assertNoSecrets(conflict.body());
            if (!local) {
                assertEquals(404, get("/v3/api-docs", token).statusCode());
                assertEquals(404, get("/actuator/env", token).statusCode());
            }
            assertNoSecrets(health.body());
        }
        assertNoSecrets(output.getAll());
    }

    @Test
    void packagedRuntimeHasActuatorAndNoH2() throws Exception {
        try (var jar = new ZipFile(Path.of("target/demo-0.0.1-SNAPSHOT.jar").toFile())) {
            var entries = jar.stream().map(entry -> entry.getName()).toList();
            assertTrue(entries.stream().anyMatch(name -> name.startsWith("BOOT-INF/lib/spring-boot-actuator-")));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("BOOT-INF/lib/h2-")));
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, Object> body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (path.endsWith("/inventory-adjustments")) request.header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).DELETE();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertNoSecrets(String value) {
        assertFalse(value.contains(PASSWORD));
        assertFalse(value.contains(SECRET));
        for (String field : List.of("datasource", "diskSpace", "components"))
            assertFalse(value.contains(field) && value.startsWith("{\"status\":"));
    }
}
