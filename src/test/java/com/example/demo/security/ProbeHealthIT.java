package com.example.demo.security;

import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.open-in-view=false", "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500"})
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ProbeHealthIT {
    private static final String SECRET = randomSecret();
    private static final String PASSWORD = randomSecret();
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2")
            .withDatabaseName("probe_health").withUsername("inventory_app").withPassword(PASSWORD)
            .withUrlParam("connectTimeout", "1000").withUrlParam("socketTimeout", "1000")
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired HealthEndpointGroups groups;
    @Autowired HealthContributorRegistry contributors;
    @Autowired DataSource dataSource;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @ParameterizedTest
    @ValueSource(strings = {"/livez", "/readyz", "/actuator/health"})
    void healthyEndpointsAreAnonymousAndMinimalOnApplicationPort(String path, CapturedOutput output) throws Exception {
        var response = get(path);
        if (path.equals("/actuator/health")) {
            assertEquals(200, response.statusCode());
            var body = json.readTree(response.body());
            assertEquals("UP", body.path("status").asText());
            var groupNames = new HashSet<String>();
            body.path("groups").forEach(group -> groupNames.add(group.asText()));
            assertEquals(Set.of("liveness", "readiness"), groupNames);
            assertFalse(body.has("details"));
            assertFalse(body.has("components"));
            assertEquals(2, body.size());
        } else {
            assertHealth(response, 200, "UP");
        }
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertEquals(id, java.util.UUID.fromString(id).toString());
        assertNoSecrets(output);
    }

    @Test
    void groupsContainOnlyTheExpectedContributors() {
        assertEquals(Set.of("livenessState"), members("liveness"));
        assertEquals(Set.of("readinessState", "db"), members("readiness"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/health/db",
            "/actuator/health/liveness", "/actuator/health/readiness"})
    void otherActuatorPathsStayUnavailable(String path) throws Exception {
        var response = get(path);
        assertEquals(404, response.statusCode());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("code").asText());
    }

    @Test
    void refusingTrafficFailsReadinessWhileLivenessStaysUp(CapturedOutput output) throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            awaitHealth("/readyz", 503, "OUT_OF_SERVICE");
            assertHealth(get("/livez"), 200, "UP");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        awaitHealth("/readyz", 200, "UP");
        assertNoSecrets(output);
    }

    @Test
    void brokenApplicationLivenessDoesNotChangeReadiness(CapturedOutput output) throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            awaitHealth("/livez", 503, "DOWN");
            assertHealth(get("/readyz"), 200, "UP");
        } finally {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        }
        awaitHealth("/livez", 200, "UP");
        assertNoSecrets(output);
    }

    @Test
    void databaseFailureStopsReadinessButNotLivenessAndRecovers(CapturedOutput output) throws Exception {
        assertHealth(get("/readyz"), 200, "UP");
        var docker = database.getDockerClient();
        boolean paused = false;
        try {
            // このtest専用containerを一時停止。既存DB/volumeには触れず、finallyで必ず復旧する。
            docker.pauseContainerCmd(database.getContainerId()).exec();
            paused = true;
            ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
            awaitHealth("/readyz", 503, "DOWN");
            assertHealth(get("/livez"), 200, "UP");
        } finally {
            if (paused) docker.unpauseContainerCmd(database.getContainerId()).exec();
            ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
        }
        awaitHealth("/readyz", 200, "UP");
        assertNoSecrets(output);
    }

    @Test
    void officialMariaDbHealthcheckSucceedsWithoutSecretArguments() throws Exception {
        var result = database.execInContainer("healthcheck.sh", "--connect", "--innodb_initialized");
        assertEquals(0, result.getExitCode());
        assertFalse((result.getStdout() + result.getStderr()).contains(PASSWORD));
        assertFalse((result.getStdout() + result.getStderr()).contains(SECRET));
    }

    private Set<String> members(String groupName) {
        var result = new HashSet<String>();
        contributors.forEach(contributor -> {
            if (groups.get(groupName).isMember(contributor.name())) result.add(contributor.name());
        });
        return result;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitHealth(String path, int status, String health) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int lastStatus = 0;
        do {
            var response = get(path);
            lastStatus = response.statusCode();
            if (lastStatus == status) {
                assertHealth(response, status, health);
                return;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        fail("Bounded health transition timed out: " + path + " status=" + lastStatus);
    }

    private void assertHealth(HttpResponse<String> response, int status, String health) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals(json.valueToTree(Map.of("status", health)), json.readTree(response.body()));
    }

    private void assertNoSecrets(CapturedOutput output) {
        assertFalse(output.getAll().contains(SECRET));
        assertFalse(output.getAll().contains(PASSWORD));
    }

    private static String randomSecret() {
        return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    }
}
