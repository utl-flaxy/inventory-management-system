package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.exception.RegistrationRateLimitExceededException;
import com.example.demo.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class RegistrationSecurityIT {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String PASSWORD = "registration-password-canary";
    private static final String KNOWN = "registration-user-canary";
    private static final AtomicInteger ROUND = new AtomicInteger();
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("registration_security").withUsername("inventory_app").withPassword("registration-db-canary");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("app.login.rate-limit.trusted-proxies", () -> "127.0.0.1");
        registry.add("JWT_SECRET", () -> SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean PasswordEncoder encoder;
    @MockitoSpyBean JwtUtil jwt;
    @Autowired RegistrationRateLimiter registrations;
    @Autowired LoginRateLimiter logins;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    private Instant start;
    private String knownHash;

    @BeforeEach
    void fixtures() {
        start = Instant.parse("2026-10-17T00:00:00Z").plusSeconds(120L * ROUND.incrementAndGet());
        when(clock.instant()).thenReturn(start);
        users.deleteAll();
        knownHash = encoder.encode(PASSWORD);
        users.saveAndFlush(new User(null, KNOWN, knownHash, "ROLE_USER"));
        clearInvocations(users, encoder, jwt);
        assertEquals(0, registrations.inFlightCount());
        assertEquals(0, logins.inFlightCount());
    }

    private ResultActions auth(String endpoint, String source, String username, String password) throws Exception {
        return mvc.perform(post("/auth/" + endpoint).with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .header("X-Forwarded-For", source).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private ResultActions register(String source, String username) throws Exception {
        return auth("register", source, username, PASSWORD);
    }

    @Test
    void anonymousRegistrationKeepsRoleUserBcryptAndJwtAuthorizationWithoutSecretFields() throws Exception {
        var body = mvc.perform(post("/auth/register").header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("username", "admin", "password", PASSWORD, "role", "ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(3, json.readTree(body).size());
        var user = users.findByUsername("admin").orElseThrow();
        assertTrue(encoder.matches(PASSWORD, user.getPassword()));
        assertFalse(body.contains(PASSWORD)); assertFalse(body.contains(user.getPassword()));
        var loggedIn = auth("login", "198.51.100.1", "admin", PASSWORD).andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(loggedIn.getContentAsString()).path("token").asText();
        assertFalse(token.isBlank());
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/orders")).andExpect(status().isUnauthorized());
        assertEquals(0, registrations.inFlightCount());
    }

    @Test
    void fiveNewAccountsThen429BeforeLookupEncodeOrInsertWithAnotherSourceStillAllowed() throws Exception {
        for (int i = 0; i < 5; i++) register("198.51.100.1", "new-account-" + i).andExpect(status().isOk());
        clearInvocations(users, encoder, jwt);
        register("198.51.100.1", "sixth-account")
                .andExpect(problem(429, "REGISTRATION_RATE_LIMITED", "Registration Rate Limited", "/auth/register"))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.detail").value(RegistrationRateLimitExceededException.DETAIL));
        verifyNoInteractions(users, encoder, jwt);
        assertEquals(6, users.count());
        register("198.51.100.2", "sixth-account").andExpect(status().isOk());
        assertEquals(7, users.count());
    }

    @Test
    void duplicateFailuresConsumeQuotaKeepOriginalHashAndResetAtExactWindowBoundary() throws Exception {
        for (int i = 0; i < 5; i++) register("198.51.100.1", KNOWN).andExpect(status().isConflict());
        verify(encoder, never()).encode(any());
        when(clock.instant()).thenReturn(start.plusSeconds(58).plusNanos(999_999_999));
        register("198.51.100.1", "later-user").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"));
        when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
        register("198.51.100.1", "later-user").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        register("198.51.100.1", "later-user").andExpect(status().isOk());
        assertTrue(knownHash.equals(users.findByUsername(KNOWN).orElseThrow().getPassword()));
        assertEquals(2, users.count());
        assertEquals(0, registrations.inFlightCount());
    }

    @Test
    void validationMalformedJsonAndMediaErrorsDoNotSpendRegistrationQuotaOrRunBcrypt() throws Exception {
        for (int i = 0; i < 6; i++) auth("register", "198.51.100.1", "ab", PASSWORD)
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/auth/register"))
                .andExpect(jsonPath("$.errors.username").isString());
        mvc.perform(post("/auth/register").header("X-Forwarded-For", "198.51.100.1")
                .contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
        mvc.perform(post("/auth/register").header("X-Forwarded-For", "198.51.100.1")
                .contentType(MediaType.TEXT_PLAIN).content("invalid")).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(users, encoder, jwt);
        for (int i = 0; i < 5; i++) register("198.51.100.1", KNOWN).andExpect(status().isConflict());
        register("198.51.100.1", KNOWN).andExpect(status().isTooManyRequests());
        assertEquals(1, users.count());
    }

    @Test
    void loginAndRegistrationExhaustionHaveIndependentCountersAndCodes() throws Exception {
        for (int i = 0; i < 5; i++) auth("login", "198.51.100.1", KNOWN, "wrong-password").andExpect(status().isUnauthorized());
        auth("login", "198.51.100.1", KNOWN, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
        register("198.51.100.1", "independent-user").andExpect(status().isOk());
        for (int i = 0; i < 5; i++) register("198.51.100.2", KNOWN).andExpect(status().isConflict());
        register("198.51.100.2", "another-user").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));
        for (int i = 0; i < 5; i++) auth("login", "198.51.100.2", KNOWN, PASSWORD).andExpect(status().isOk());
        auth("login", "198.51.100.2", KNOWN, PASSWORD).andExpect(status().isTooManyRequests());
        assertEquals(0, registrations.inFlightCount()); assertEquals(0, logins.inFlightCount());
    }

    @Test
    @Timeout(30)
    void twentyParallelRequestsAdmitTwoWithoutQueueAndLeaveLoginPermitAndRejectedSourceQuotaUntouched() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(encoder).encode(PASSWORD);
        var executor = Executors.newFixedThreadPool(20);
        var first = new ArrayList<Future<Integer>>(); var rejected = new ArrayList<Future<Integer>>();
        var ready = new CountDownLatch(18); var go = new CountDownLatch(1);
        try {
            for (int i = 1; i <= 2; i++) {
                int index = i;
                first.add(executor.submit(() -> register("198.51.100." + index, "parallel-user-" + index)
                        .andReturn().getResponse().getStatus()));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, registrations.inFlightCount()); assertEquals(0, logins.inFlightCount());
            clearInvocations(users);
            for (int i = 3; i <= 20; i++) {
                int index = i;
                rejected.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                    return register("198.51.100." + index, "parallel-user-" + index)
                            .andExpect(header().string("Retry-After", "1"))
                            .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            for (var request : rejected) assertEquals(429, request.get(5, TimeUnit.SECONDS));
            verifyNoInteractions(users);
            auth("login", "203.0.113.80", KNOWN, PASSWORD).andExpect(status().isOk());
            release.countDown();
            for (var request : first) assertEquals(200, request.get(10, TimeUnit.SECONDS));
            assertEquals(0, registrations.inFlightCount());
            register("198.51.100.3", "after-completion").andExpect(status().isOk());
            for (int i = 0; i < 4; i++) register("198.51.100.3", KNOWN).andExpect(status().isConflict());
            register("198.51.100.3", KNOWN).andExpect(status().isTooManyRequests());
            verify(encoder, times(3)).encode(PASSWORD);
            assertEquals(4, users.count());
        } finally {
            go.countDown(); release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "encode", "save"})
    void unexpectedFailureConsumesAttemptReleasesPermitAndKeepsSafeDiagnostics(String stage, CapturedOutput output) throws Exception {
        for (int i = 0; i < 4; i++) register("198.51.100.123", KNOWN).andExpect(status().isConflict());
        var failure = new DataAccessResourceFailureException("SQL registration-password-canary database-exception-canary");
        switch (stage) {
            case "lookup" -> doThrow(failure).when(users).findByUsername("db-failure-canary");
            case "encode" -> doThrow(failure).when(encoder).encode(PASSWORD);
            default -> doThrow(failure).when(users).saveAndFlush(any(User.class));
        }
        register("198.51.100.123", "db-failure-canary")
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/auth/register"));
        assertEquals(0, registrations.inFlightCount());
        if (stage.equals("lookup")) verifyNoInteractions(encoder);
        else verify(encoder).encode(PASSWORD);
        verifyNoInteractions(jwt);
        doCallRealMethod().when(encoder).encode(PASSWORD);
        // Spring Data's interface spy delegates to the repository proxy; it has no concrete real method.
        reset(users);
        assertEquals(1, users.count());
        register("198.51.100.123", "after-failure").andExpect(status().isTooManyRequests());
        register("198.51.100.124", "after-failure").andExpect(status().isOk());
        assertEquals(2, users.count());
        for (String value : List.of(PASSWORD, "database-exception-canary", "db-failure-canary", "198.51.100.123"))
            assertFalse(output.getAll().contains(value));
        assertTrue(output.getAll().contains("system.unhandled_error"));
    }

    @Test
    void untrustedPeerCannotRotateForgedForwardingAndInvalidSourcePrecedesValidation() throws Exception {
        for (int i = 1; i <= 6; i++) mvc.perform(post("/auth/register")
                        .with(request -> { request.setRemoteAddr("198.51.100.1"); return request; })
                        .header("X-Forwarded-For", "203.0.113." + i)
                        .header("Forwarded", "for=203.0.113." + i)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(problem(503, "REGISTRATION_SOURCE_UNAVAILABLE", "Registration Source Unavailable", "/auth/register"))
                .andExpect(header().doesNotExist("Retry-After"));
        verifyNoInteractions(users, encoder, jwt);
    }

    private HttpResponse<String> httpRegister(String source, boolean forgedForwarded) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/register"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", KNOWN, "password", PASSWORD))));
        if (source != null) request.header("X-Forwarded-For", source);
        if (forgedForwarded) request.header("Forwarded", "for=203.0.113.90");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realProdHttpServerSeparatesTrustedClientsAndRejectsMalformedOrMissingClaims() throws Exception {
        for (String source : List.of("203.0.113.1", "203.0.113.2")) {
            for (int i = 0; i < 5; i++) assertEquals(409, httpRegister(source, false).statusCode());
            var limited = httpRegister(source, false);
            assertEquals(429, limited.statusCode());
            assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow());
            assertEquals("REGISTRATION_RATE_LIMITED", json.readTree(limited.body()).path("code").asText());
        }
        for (var rejected : List.of(httpRegister(null, false), httpRegister("203.0.113.90", true),
                httpRegister("203.0.113.1, 203.0.113.2", false))) {
            assertEquals(503, rejected.statusCode());
            assertEquals("REGISTRATION_SOURCE_UNAVAILABLE", json.readTree(rejected.body()).path("code").asText());
            assertEquals(6, json.readTree(rejected.body()).size());
            assertTrue(rejected.headers().firstValue("Retry-After").isEmpty());
            assertTrue(rejected.headers().firstValue("X-Request-Id").isPresent());
        }
        assertEquals(409, httpRegister("203.0.113.90", false).statusCode());
        assertEquals(1, users.count()); verifyNoInteractions(encoder, jwt);
    }

    @Test
    void productionLogsAndRejectionBodiesDoNotDiscloseCredentialsHashesJwtOrSource(CapturedOutput output) throws Exception {
        register("198.51.100.123", "new-user-canary").andExpect(status().isOk());
        String hash = users.findByUsername("new-user-canary").orElseThrow().getPassword();
        String token = json.readTree(auth("login", "198.51.100.123", "new-user-canary", PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();
        for (int i = 0; i < 4; i++) register("198.51.100.123", KNOWN).andExpect(status().isConflict());
        String limited = register("198.51.100.123", "rejected-user-canary").andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();
        for (String value : List.of(KNOWN, PASSWORD, SECRET, hash, knownHash, token, "198.51.100.123",
                "new-user-canary", "rejected-user-canary", database.getPassword())) {
            assertFalse(output.getAll().contains(value)); assertFalse(limited.contains(value));
        }
        assertTrue(output.getAll().contains("auth.register.succeeded"));
        assertTrue(output.getAll().contains("auth.register.throttled"));
        assertEquals(2, users.count());
    }
}
