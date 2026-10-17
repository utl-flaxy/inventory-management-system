package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.exception.LoginRateLimitExceededException;
import com.example.demo.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.open-in-view=false", "app.login.rate-limit.pair-limit=2",
        "app.login.rate-limit.source-limit=4", "app.login.rate-limit.max-concurrent=4",
        "app.login.rate-limit.max-entries=16", "app.login.rate-limit.trusted-proxies=127.0.0.1"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LoginSecurityIT {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String PASSWORD = "login-password-canary";
    private static final String USERNAME = "login-user-canary";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("login_security").withUsername("inventory_app");

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
    @Autowired LoginDummyPassword dummy;
    @Autowired LoginRateLimiter limiter;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    private Instant start;
    private static final java.util.concurrent.atomic.AtomicInteger ROUND = new java.util.concurrent.atomic.AtomicInteger();
    private String hash;

    @BeforeEach
    void fixtures() {
        start = Instant.parse("2026-10-11T00:00:00Z").plusSeconds(120L * ROUND.incrementAndGet());
        when(clock.instant()).thenReturn(start);
        users.deleteAll();
        hash = encoder.encode(PASSWORD);
        users.saveAndFlush(new User(null, USERNAME, hash, "ROLE_USER"));
        clearInvocations(users, encoder, jwt);
    }

    private ResultActions login(String source, String username, String password) throws Exception {
        return mvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .header("X-Forwarded-For", source).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    @Test
    void knownAndUnknownFailureDoOneBcryptMatchEachAndHaveIdentical401WithoutJwt() throws Exception {
        String known = login("198.51.100.1", USERNAME, "wrong-password-canary").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String absent = login("198.51.100.1", "unknown-user-canary", "wrong-password-canary")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(known, absent);
        verify(encoder).matches("wrong-password-canary", hash);
        verify(encoder).matches("wrong-password-canary", dummy.encoded());
        verify(encoder, times(2)).matches(any(), any());
        verify(encoder, never()).encode(any());
        verify(users).findByUsername(USERNAME); verify(users).findByUsername("unknown-user-canary");
        verifyNoMoreInteractions(users); verifyNoInteractions(jwt);
        assertEquals(hash.substring(0, 7), dummy.encoded().substring(0, 7));
    }

    @Test
    void successUsesTheSameQuotaAndReturnsJwtThatAuthenticatesProtectedEndpoint() throws Exception {
        for (int i = 0; i < 2; i++) {
            var response = login("198.51.100.1", USERNAME, PASSWORD).andExpect(status().isOk()).andReturn().getResponse();
            String token = json.readTree(response.getContentAsString()).path("token").asText();
            assertFalse(token.isEmpty());
            mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        clearInvocations(users, encoder, jwt);
        login("198.51.100.1", USERNAME, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        verifyNoInteractions(users, encoder, jwt);
    }

    @Test
    void thresholdRecoveryAndValidationPreserve401429400AndRegistrationHasIndependentQuota() throws Exception {
        for (int i = 0; i < 2; i++) login("198.51.100.1", USERNAME, "wrong-password").andExpect(status().isUnauthorized());
        clearInvocations(users, encoder, jwt);
        var response = login("198.51.100.1", USERNAME, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value(LoginRateLimitExceededException.DETAIL))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(USERNAME)); assertFalse(response.contains(PASSWORD)); assertFalse(response.contains(hash));
        login("198.51.100.1", USERNAME, "short").andExpect(status().isBadRequest());
        verifyNoInteractions(users, encoder, jwt);
        when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
        login("198.51.100.1", USERNAME, PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        login("198.51.100.1", USERNAME, PASSWORD).andExpect(status().isOk());
        mvc.perform(post("/auth/register").header("X-Forwarded-For", "198.51.100.1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "another-user", "password", PASSWORD))))
                .andExpect(status().isOk());
        assertEquals(2, users.count());
        assertEquals(hash, users.findByUsername(USERNAME).orElseThrow().getPassword());
    }

    @Test
    @Timeout(30)
    void concurrentLoginsAdmitExactlyTwoAndThrottledRequestsNeverReachDbOrBcrypt() throws Exception {
        var executor = Executors.newFixedThreadPool(12);
        var ready = new CountDownLatch(12); var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Integer>>();
        try {
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                return login("198.51.100.1", USERNAME, "wrong-password").andReturn().getResponse().getStatus();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int unauthorized = 0, limited = 0;
            for (var future : futures) {
                int status = future.get(10, TimeUnit.SECONDS);
                if (status == 401) unauthorized++; else { assertEquals(429, status); limited++; }
            }
            assertEquals(2, unauthorized); assertEquals(10, limited);
            verify(users, times(2)).findByUsername(USERNAME); verifyNoMoreInteractions(users);
            verify(encoder, times(2)).matches("wrong-password", hash); verifyNoInteractions(jwt);
        } finally { go.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void changingUsernameCannotBypassSourceQuotaButOtherSourcesCanStillAuthenticate() throws Exception {
        for (int i = 0; i < 4; i++) login("198.51.100.1", "unknown-" + i, PASSWORD).andExpect(status().isUnauthorized());
        login("198.51.100.1", "new-unknown", PASSWORD).andExpect(status().isTooManyRequests());
        login("198.51.100.2", USERNAME, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void untrustedTransportIgnoresSpoofedHeadersAcrossBothFrameworkAndSecurityChains() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(post("/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.1"); return request; })
                        .header("X-Forwarded-For", "203.0.113." + (i + 1))
                        .header("Forwarded", "for=203.0.113." + (i + 10) + ";proto=https")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", USERNAME, "password", "wrong-password"))))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(encoder);
    }

    private HttpResponse<String> httpLogin(String client, boolean forgedForwarded) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Forwarded-For", client).POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("username", USERNAME, "password", "wrong-password"))));
        if (forgedForwarded) request.header("Forwarded", "for=" + client + ";proto=https");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realEmbeddedProdServerCapturesSocketBeforeForwardingAndSeparatesTrustedProxyClients() throws Exception {
        for (String client : List.of("203.0.113.1", "203.0.113.2")) {
            assertEquals(401, httpLogin(client, false).statusCode());
            assertEquals(401, httpLogin(client, false).statusCode());
            assertEquals(429, httpLogin(client, false).statusCode());
        }
        // Reject malformed trusted claims without charging the proxy or another client.
        for (int i = 3; i <= 5; i++) {
            var rejected = httpLogin("203.0.113." + i, true);
            assertEquals(503, rejected.statusCode());
            assertEquals("LOGIN_SOURCE_UNAVAILABLE", json.readTree(rejected.body()).path("code").asText());
            assertEquals(6, json.readTree(rejected.body()).size());
            assertTrue(rejected.headers().firstValue("Retry-After").isEmpty());
            assertTrue(rejected.headers().firstValue("X-Request-Id").isPresent());
        }
    }

    @Test
    void productionOutputIncludingFrameworkLoggersNeverContainsLoginSecretsOrSourceAddresses(CapturedOutput output) throws Exception {
        String token = json.readTree(login("198.51.100.123", USERNAME, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        login("198.51.100.123", "unknown-user-canary", "wrong-password-canary").andExpect(status().isUnauthorized());
        login("198.51.100.123", USERNAME, "wrong-password-canary").andExpect(status().isUnauthorized());
        login("198.51.100.123", USERNAME, PASSWORD).andExpect(status().isTooManyRequests());
        // Audit actual console output, never responses or source literals.
        String console = output.getAll();
        for (String value : List.of(USERNAME, PASSWORD, "unknown-user-canary", "wrong-password-canary", hash,
                dummy.encoded(), SECRET, token, "198.51.100.123")) assertFalse(console.contains(value));
        var events = new HashSet<String>();
        for (String line : console.lines().toList()) if (line.startsWith("{")) {
            var record = json.readTree(line);
            String event = record.path("event").asText();
            if (event.startsWith("auth.login.")) {
                events.add(event);
                assertTrue(record.hasNonNull("requestId"));
            }
        }
        assertTrue(events.containsAll(Set.of("auth.login.succeeded", "auth.login.failed", "auth.login.throttled")));
        assertTrue(limiter.entryCount() <= 16);
    }

    @Test
    void completedAttackerFailuresCannotSpendAProcessWideMinuteBudgetForAnotherClient() throws Exception {
        for (int source = 1; source <= 5; source++)
            for (int attempt = 0; attempt < 2; attempt++)
                login("198.51.100." + source, USERNAME, "wrong-password").andExpect(status().isUnauthorized());
        login("203.0.113.80", USERNAME, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void malformedTrustedProxyClaimsDoNotAllocateSharedPeerQuota() throws Exception {
        assertEquals(503, httpLogin("203.0.113.3", true).statusCode());
        login("203.0.113.80", USERNAME, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @Timeout(30)
    void expensiveAuthenticationIsBoundedAndCompletionImmediatelyReadmitsOtherClients() throws Exception {
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(encoder).matches("wrong-password", hash);
        var executor = Executors.newFixedThreadPool(4);
        var requests = new ArrayList<Future<Integer>>();
        try {
            for (int i = 1; i <= 4; i++) {
                String source = "198.51.100." + i;
                requests.add(executor.submit(() -> login(source, USERNAME, "wrong-password")
                        .andReturn().getResponse().getStatus()));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(4, limiter.inFlightCount());
            clearInvocations(users);
            login("203.0.113.80", USERNAME, PASSWORD).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
            verifyNoInteractions(users);
            release.countDown();
            for (var request : requests) assertEquals(401, request.get(10, TimeUnit.SECONDS));
            assertEquals(0, limiter.inFlightCount());
            login("203.0.113.80", USERNAME, PASSWORD).andExpect(status().isOk());
            assertEquals(0, limiter.inFlightCount());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unexpectedLoginDatabaseFailureKeepsSafe500AndApplicationDiagnostics(CapturedOutput output) throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "SQL login-user-canary login-password-canary database-exception-canary"))
                .when(users).findByUsername(USERNAME);
        login("198.51.100.123", USERNAME, PASSWORD).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"));
        verifyNoInteractions(encoder, jwt);
        assertEquals(0, limiter.inFlightCount());
        String console = output.getAll();
        for (String value : List.of(USERNAME, PASSWORD, "database-exception-canary", "198.51.100.123", hash,
                dummy.encoded(), SECRET)) assertFalse(console.contains(value));
        assertTrue(console.lines().filter(line -> line.startsWith("{")).map(line -> {
            try { return json.readTree(line); } catch (Exception ex) { throw new AssertionError(ex); }
        }).anyMatch(record -> "system.unhandled_error".equals(record.path("event").asText())
                && "ERROR".equals(record.path("level").asText()) && record.hasNonNull("requestId")));
    }
}
