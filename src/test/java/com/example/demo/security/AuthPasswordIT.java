package com.example.demo.security;

import com.example.demo.TestAuth;
import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthRevocationTransactions;
import com.example.demo.service.PasswordChangeService;
import com.example.demo.service.PasswordChangeTransactions;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AuthPasswordIT {
    private static String random() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
    private static final String SECRET = random(), PASSWORD = random(), NEW_PASSWORD = random();
    private static final AtomicInteger SOURCES = new AtomicInteger();
    private static final String AUDIT_SQL = "INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES (?,?,'PASSWORD_CHANGE','SUCCEEDED',?,?)";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("auth_password_b2").withUsername("inventory_app").withPassword(random())
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", database::getJdbcUrl);r.add("spring.datasource.username", database::getUsername);
        r.add("spring.datasource.password", database::getPassword);r.add("spring.datasource.driver-class-name", database::getDriverClassName);
        r.add("app.login.rate-limit.trusted-proxies", () -> "127.0.0.1");r.add("JWT_SECRET", () -> SECRET);
    }
    @LocalServerPort int port;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean JdbcTemplate jdbc;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean JwtUserAuthenticator authenticator;
    @MockitoSpyBean PasswordChangeTransactions changes;
    @Autowired PasswordChangeService service;
    @Autowired AuthRevocationTransactions revocations;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired JwtUtil jwt;
    @Autowired AuthMutationRateLimiter limiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private AuthPrincipal alice, bob, admin;
    private String token, bobToken, adminToken, originalHash;

    @BeforeEach void fixture() {
        for (String table : List.of("auth_revocation_audit", "inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table); // Only the disposable database created above.
        originalHash = passwords.encode(PASSWORD);
        for (String name : List.of("password_alice", "password_bob", "password_admin"))
            users.saveAndFlush(User.builder().username(name).password(originalHash).role(name.endsWith("admin") ? "ROLE_ADMIN" : "ROLE_USER").build());
        alice = TestAuth.principal(jdbc, "password_alice");bob = TestAuth.principal(jdbc, "password_bob");admin = TestAuth.principal(jdbc, "password_admin");
        token = jwt.generateToken(alice.uid(), 0);bobToken = jwt.generateToken(bob.uid(), 0);adminToken = jwt.generateToken(admin.uid(), 0);
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'password-business-canary',100,10,0)");
        clearInvocations(passwords, users, changes, authenticator);
    }
    @AfterEach void noBusinessMutationOrLeakedConnectionAndPermit() throws Exception {
        assertEquals(List.of(10), jdbc.queryForList("SELECT stock FROM products", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id=1", Long.class));
        for (String table : List.of("orders", "order_items", "inventory_movements"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        assertEquals(0, limiter.inFlightCount());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertEquals(0, dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()));
    }
    private String nextSource() { int n = SOURCES.incrementAndGet();return "198.19." + (n / 250) + "." + (n % 250); }
    private HttpResponse<String> send(String method, String path, String bearer, Object body, String client) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (client != null) request.header("X-Forwarded-For", client);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> change(String bearer, String current, String replacement) throws Exception {
        return send("POST", "/auth/password", bearer, Map.of("currentPassword", current, "newPassword", replacement), nextSource());
    }
    private HttpResponse<String> get(String bearer) throws Exception { return send("GET", "/api/v1/me/orders", bearer, null, nextSource()); }
    private HttpResponse<String> login(String name, String password) throws Exception {
        return send("POST", "/auth/login", null, Map.of("username", name, "password", password), nextSource());
    }
    private long version() { return jdbc.queryForObject("SELECT token_version FROM users WHERE auth_uid=?", Long.class, alice.uid()); }
    private int audits() { return jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_audit", Integer.class); }
    private String hash() { return jdbc.queryForObject("SELECT password FROM users WHERE auth_uid=?", String.class, alice.uid()); }
    private void unchanged() { assertEquals(0, version());assertTrue(originalHash.equals(hash()), "Credential changed");assertEquals(0, audits());assertEquals(0, limiter.inFlightCount()); }
    private void problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());var body = json.readTree(response.body());
        assertEquals(code.equals("VALIDATION_ERROR") ? 7 : 6, body.size());assertEquals(code, body.path("code").asText());
        assertEquals(status, body.path("status").asInt());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertNotNull(response.headers().firstValue("X-Request-Id").orElse(null));
        for (String secret : List.of(SECRET, PASSWORD, NEW_PASSWORD, originalHash, token, "password_alice", "jdbc:"))
            assertFalse(response.body().contains(secret), "Authentication data leaked");
    }
    private PasswordChangeTransactions.Snapshot snapshot() {
        return new PasswordChangeTransactions.Snapshot(alice.id(), alice.uid(), 0, originalHash, "ROLE_USER");
    }

    @Test void commitsHashVersionAuditAndRevokesAllDevicesWithoutChangingAnotherUser(CapturedOutput output) throws Exception {
        String secondDevice = new JwtUtil(SECRET, "inventory-management-system", 3600000,
                java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofMinutes(-1)))
                .generateToken(alice.uid(), 0);
        assertFalse(token.equals(secondDevice), "The two valid device tokens must be distinct without sleeping");
        assertEquals(new JwtUtil.VerifiedJwt(alice.uid(), 0), jwt.verify(secondDevice));
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return call.callRealMethod(); })
                .when(passwords).matches(eq(PASSWORD), anyString());
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return call.callRealMethod(); })
                .when(passwords).encode(NEW_PASSWORD);
        var result = send("POST", "/auth/password", token, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD, "uid", bob.uid(), "role", "ROLE_ADMIN"), nextSource());
        assertEquals(204, result.statusCode());assertTrue(result.body().isEmpty());assertEquals(1, version());assertEquals(1, audits());
        assertTrue(passwords.matches(NEW_PASSWORD, hash()));assertFalse(passwords.matches(PASSWORD, hash()));
        assertEquals("PASSWORD_CHANGE", jdbc.queryForObject("SELECT action FROM auth_revocation_audit", String.class));
        assertEquals(alice.id(), jdbc.queryForObject("SELECT actor_user_id FROM auth_revocation_audit", Long.class));
        assertEquals(alice.id(), jdbc.queryForObject("SELECT target_user_id FROM auth_revocation_audit", Long.class));
        assertEquals(result.headers().firstValue("X-Request-Id").orElseThrow(), jdbc.queryForObject("SELECT request_id FROM auth_revocation_audit", String.class));
        problem(get(token), 401, "UNAUTHENTICATED");problem(get(secondDevice), 401, "UNAUTHENTICATED");
        problem(change(token, NEW_PASSWORD, PASSWORD), 401, "UNAUTHENTICATED");
        problem(login("password_alice", PASSWORD), 401, "INVALID_CREDENTIALS");
        var relogin = login("password_alice", NEW_PASSWORD);assertEquals(200, relogin.statusCode());
        String fresh = json.readTree(relogin.body()).path("token").asText();assertEquals(new JwtUtil.VerifiedJwt(alice.uid(), 1), jwt.verify(fresh));
        assertEquals(200, get(fresh).statusCode());assertEquals(200, get(bobToken).statusCode());
        assertEquals(200, send("GET", "/api/v1/admin/orders", adminToken, null, nextSource()).statusCode());
        problem(send("GET", "/api/v1/admin/orders", fresh, null, nextSource()), 403, "ACCESS_DENIED");
        assertEquals(0L, jdbc.queryForObject("SELECT token_version FROM users WHERE id=?", Long.class, bob.id()));
        assertTrue(originalHash.equals(jdbc.queryForObject("SELECT password FROM users WHERE id=?", String.class, bob.id())), "Other credential changed");
        for (String value : List.of(SECRET, PASSWORD, NEW_PASSWORD, originalHash, hash(), token, secondDevice, fresh,
                "password_alice", database.getPassword(), database.getJdbcUrl()))
            assertFalse(output.getAll().contains(value), "Authentication data entered logs");
        assertTrue(output.getAll().contains("auth.password_change.succeeded"));assertEquals(0, limiter.inFlightCount());
    }

    @Test void wrongCurrentPasswordIs401WithoutEncodingOrDatabaseMutation() throws Exception {
        problem(change(token, random(), NEW_PASSWORD), 401, "INVALID_CREDENTIALS");
        verify(passwords, times(1)).matches(anyString(), anyString());verify(passwords, never()).encode(any());
        verifyNoInteractions(changes);unchanged();assertEquals(200, get(token).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"missing-current", "missing-new", "short-current", "short-new", "long-current", "long-new", "utf8-current", "utf8-new", "blank-new"})
    void invalidFieldsAre400WithoutCredentialWorkOrDatabaseWrites(String scenario) throws Exception {
        var body = new HashMap<String, String>();body.put("currentPassword", PASSWORD);body.put("newPassword", NEW_PASSWORD);
        String field = scenario.endsWith("current") ? "currentPassword" : "newPassword";
        if (scenario.startsWith("missing")) body.remove(field);
        else body.put(field, scenario.startsWith("short") ? "short" : scenario.startsWith("long") ? "a".repeat(73)
                : scenario.startsWith("utf8") ? "あ".repeat(25) : " ".repeat(16));
        var result = send("POST", "/auth/password", token, body, nextSource());problem(result, 400, "VALIDATION_ERROR");
        assertTrue(json.readTree(result.body()).path("errors").has(field));verifyNoInteractions(passwords, changes);unchanged();
    }

    static java.util.stream.Stream<String> validPasswords() {
        return java.util.stream.Stream.of("12345678", "あ".repeat(24), "a".repeat(72), "  padded-secret  ");
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("validPasswords")
    void userNewPasswordBoundariesAreAcceptedWithoutTrimming(String replacement) throws Exception {
        assertEquals(204, change(token, PASSWORD, replacement).statusCode());assertTrue(passwords.matches(replacement, hash()));
        assertEquals(200, login("password_alice", replacement).statusCode());
        if (replacement.startsWith(" ")) problem(login("password_alice", replacement.trim()), 401, "INVALID_CREDENTIALS");
    }

    @ParameterizedTest @ValueSource(strings = {"too-short", "control"})
    void administratorNewPasswordUsesTheOfflineProvisioningPolicy(String scenario) throws Exception {
        String replacement = scenario.equals("too-short") ? "a".repeat(15) : "a".repeat(16) + "\n";
        var response = change(adminToken, PASSWORD, replacement);problem(response, 400, "VALIDATION_ERROR");
        assertTrue(json.readTree(response.body()).path("errors").has("newPassword"));verifyNoInteractions(passwords, changes);unchanged();
    }

    @Test void malformedAdministratorUnicodeIsSentAsAnExplicitJsonEscapeAndRejected() throws Exception {
        // A Java HttpClient String body would replace an unpaired surrogate when encoding UTF-8.
        String document = "{\"currentPassword\":" + json.writeValueAsString(PASSWORD)
                + ",\"newPassword\":\"" + "a".repeat(16) + "\\ud800\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/password"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + adminToken)
                .header("X-Forwarded-For", nextSource()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());problem(response, 400, "VALIDATION_ERROR");
        assertTrue(json.readTree(response.body()).path("errors").has("newPassword"));verifyNoInteractions(passwords, changes);unchanged();
    }

    @Test void administratorCanChangeItsOwnPasswordAndRetainsAuthorizationAfterRelogin() throws Exception {
        String replacement = "a".repeat(16);
        assertEquals(204, change(adminToken, PASSWORD, replacement).statusCode());problem(get(adminToken), 401, "UNAUTHENTICATED");
        var response = login("password_admin", replacement);assertEquals(200, response.statusCode());
        String fresh = json.readTree(response.body()).path("token").asText();
        assertEquals(200, send("GET", "/api/v1/admin/orders", fresh, null, nextSource()).statusCode());assertEquals(0, version());
    }

    @Test void legacyAdministratorCurrentCredentialUsesLoginRulesInsteadOfTheNewPasswordMinimum() throws Exception {
        String legacy = random().substring(0, 8);
        jdbc.update("UPDATE users SET password=? WHERE id=?", passwords.encode(legacy), admin.id());
        assertEquals(204, change(adminToken, legacy, NEW_PASSWORD).statusCode());
        assertEquals(200, login("password_admin", NEW_PASSWORD).statusCode());assertEquals(0, version());
    }

    @Test void samePasswordStillGetsANewHashAndRevokesAllOldTokens() throws Exception {
        assertEquals(204, change(token, PASSWORD, PASSWORD).statusCode());assertEquals(1, version());assertEquals(1, audits());
        assertFalse(originalHash.equals(hash()), "BCrypt must generate a new salt");assertTrue(passwords.matches(PASSWORD, hash()));
        problem(get(token), 401, "UNAUTHENTICATED");assertEquals(200, login("password_alice", PASSWORD).statusCode());
    }

    @Test void passwordChangeAndLogoutAllShareConcurrencyButNotPerOperationWindows() throws Exception {
        String client = nextSource();
        for (int i = 0; i < 5; i++) problem(send("POST", "/auth/password", token,
                Map.of("currentPassword", "incorrect-password", "newPassword", NEW_PASSWORD), client), 401, "INVALID_CREDENTIALS");
        var limited = send("POST", "/auth/password", token, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), client);
        problem(limited, 429, "AUTH_MUTATION_RATE_LIMITED");assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        unchanged();assertEquals(204, send("POST", "/auth/logout-all", token, null, client).statusCode());
        assertEquals(1, audits());assertEquals(1, version());
    }

    @Test void saturatedSharedPermitsRejectBeforeBcryptAndReleaseAfterFailures() throws Exception {
        try (var one = limiter.acquire(AuthMutationRateLimiter.Operation.LOGOUT_ALL, nextSource());
             var two = limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, nextSource())) {
            problem(change(token, PASSWORD, NEW_PASSWORD), 429, "AUTH_MUTATION_RATE_LIMITED");verifyNoInteractions(passwords, changes);
        }
        unchanged();assertEquals(204, change(token, PASSWORD, NEW_PASSWORD).statusCode());
    }

    @Test void anonymousAndUnverifiableProxySourcesCannotChangeCredentials() throws Exception {
        problem(change(null, PASSWORD, NEW_PASSWORD), 401, "UNAUTHENTICATED");
        problem(send("POST", "/auth/password", token, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), "203.0.113.1, 203.0.113.2"),
                503, "AUTH_MUTATION_SOURCE_UNAVAILABLE");verifyNoInteractions(passwords, changes);unchanged();
    }

    @Test void simultaneousPasswordChangesHaveOneCommitAndDoNotUpgradeStaleProof() throws Exception {
        runRace(false);
    }
    @Test void simultaneousLogoutAndPasswordChangeHaveOneCommitAndOneStaleRejection() throws Exception {
        runRace(true);
    }
    private void runRace(boolean logout) throws Exception {
        var entered = new CountDownLatch(2);var release = new CountDownLatch(1);
        var locking = new CountDownLatch(2);var connections = ConcurrentHashMap.<Long>newKeySet();
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));locking.countDown();
            assertTrue(locking.await(8, TimeUnit.SECONDS));
            return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(users).findByAuthUidForUpdate(alice.uid());
        doAnswer(call -> {var principal = call.callRealMethod();entered.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return principal;})
                .when(authenticator).authenticate(new JwtUtil.VerifiedJwt(alice.uid(), 0));
        String alternative = random();var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> change(token, PASSWORD, NEW_PASSWORD));
            var second = workers.submit(() -> logout ? send("POST", "/auth/logout-all", token, null, nextSource()) : change(token, PASSWORD, alternative));
            assertTrue(entered.await(8, TimeUnit.SECONDS));release.countDown();
            var a = first.get(12, TimeUnit.SECONDS);var b = second.get(12, TimeUnit.SECONDS);
            assertEquals(1, (a.statusCode() == 204 ? 1 : 0) + (b.statusCode() == 204 ? 1 : 0));
            problem(a.statusCode() == 401 ? a : b, 401, "UNAUTHENTICATED");
            assertEquals(1, version());assertEquals(1, audits());
            assertEquals(2, connections.size(), "The requests must contend through different MariaDB transaction connections");
            if (a.statusCode() == 204) assertTrue(passwords.matches(NEW_PASSWORD, hash()));
            else if (logout) assertTrue(originalHash.equals(hash()), "Losing password change stored credentials");
            else assertTrue(passwords.matches(alternative, hash()));
            assertEquals(0, limiter.inFlightCount());
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void loginCannotIssueANewGenerationFromCredentialsCheckedBeforePasswordChangeCommit() throws Exception {
        var verified = new CountDownLatch(1);var release = new CountDownLatch(1);var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var result = call.callRealMethod();
            if (first.compareAndSet(true, false)) {verified.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));}
            return result;
        }).when(passwords).matches(PASSWORD, originalHash);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> login("password_alice", PASSWORD));assertTrue(verified.await(8, TimeUnit.SECONDS));
            assertEquals(204, change(token, PASSWORD, NEW_PASSWORD).statusCode());release.countDown();
            var response = pending.get(12, TimeUnit.SECONDS);problem(response, 401, "INVALID_CREDENTIALS");
            assertFalse(json.readTree(response.body()).has("token"));assertEquals(1, version());assertEquals(1, audits());
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void passwordBcryptCannotAccidentallyRunInsideAnExistingTransaction() {
        var request = new PasswordChangeRequest();request.setCurrentPassword(PASSWORD);request.setNewPassword(NEW_PASSWORD);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () ->
                new TransactionTemplate(manager).execute(status -> {service.change(alice, request, UUID.randomUUID().toString());return null;}));
        verifyNoInteractions(passwords, changes);unchanged();
    }

    @Test void passwordProofBeforeLogoutCommitCannotChangeTheNewGeneration() throws Exception {
        var verified = new CountDownLatch(1);var release = new CountDownLatch(1);
        doAnswer(call -> {assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var result = call.callRealMethod();
            verified.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return result;}).when(passwords).matches(PASSWORD, originalHash);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> change(token, PASSWORD, NEW_PASSWORD));assertTrue(verified.await(8, TimeUnit.SECONDS));
            revocations.logoutAll(alice, UUID.randomUUID().toString());release.countDown();
            problem(pending.get(12, TimeUnit.SECONDS), 401, "UNAUTHENTICATED");assertEquals(1, version());assertEquals(1, audits());
            assertTrue(originalHash.equals(hash()), "Stale proof changed credentials");
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void hashChangeWithoutGenerationChangeStillInvalidatesThePrecomputedProof() throws Exception {
        String different = passwords.encode(random());jdbc.update("UPDATE users SET password=? WHERE id=?", different, alice.id());
        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                () -> changes.change(alice, snapshot(), passwords.encode(NEW_PASSWORD), UUID.randomUUID().toString()));
        assertEquals(0, version());assertEquals(0, audits());assertTrue(different.equals(hash()), "Previous hash comparison bypassed");
    }

    @Test void auditInsertionFailureRollsBackBothCredentialAndGenerationAndDoesNotEmitSuccess(CapturedOutput output) throws Exception {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Injected audit rejection"))
                .when(jdbc).update(eq(AUDIT_SQL), eq(alice.id()), eq(alice.id()), any(Timestamp.class), anyString());
        problem(change(token, PASSWORD, NEW_PASSWORD), 500, "SYSTEM_ERROR");unchanged();assertEquals(200, get(token).statusCode());
        assertEquals(200, login("password_alice", PASSWORD).statusCode());assertFalse(output.getAll().contains("auth.password_change.succeeded"));
    }

    @Test void transactionRollbackDoesNotExposeUncommittedHashGenerationOrAudit() throws Exception {
        String replacement = passwords.encode(NEW_PASSWORD);var updated = new CountDownLatch(1);var finish = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> new TransactionTemplate(manager).execute(status -> {
                changes.change(alice, snapshot(), replacement, UUID.randomUUID().toString());updated.countDown();
                try {assertTrue(finish.await(8, TimeUnit.SECONDS));} catch (InterruptedException failure) {throw new IllegalStateException(failure);}
                status.setRollbackOnly();return null;
            }));
            assertTrue(updated.await(8, TimeUnit.SECONDS));assertEquals(0, version());assertEquals(0, audits());
            assertTrue(originalHash.equals(hash()), "Dirty credential read");assertEquals(200, get(token).statusCode());
            finish.countDown();pending.get(8, TimeUnit.SECONDS);unchanged();
        } finally {finish.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void oldJwtIsAcceptedBeforeCommitAndRejectedByNewAuthenticationAfterCommit() throws Exception {
        String replacement = passwords.encode(NEW_PASSWORD);var updated = new CountDownLatch(1);var finish = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> new TransactionTemplate(manager).execute(status -> {
                changes.change(alice, snapshot(), replacement, UUID.randomUUID().toString());updated.countDown();
                try {assertTrue(finish.await(8, TimeUnit.SECONDS));} catch (InterruptedException failure) {throw new IllegalStateException(failure);}return null;
            }));
            assertTrue(updated.await(8, TimeUnit.SECONDS));assertEquals(200, get(token).statusCode());assertEquals(0, audits());
            finish.countDown();pending.get(8, TimeUnit.SECONDS);problem(get(token), 401, "UNAUTHENTICATED");
            assertEquals(1, version());assertEquals(1, audits());assertEquals(200, login("password_alice", NEW_PASSWORD).statusCode());
        } finally {finish.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(strings = {"transient", "unknown", "configuration"})
    void serverFailuresAre503Or500AndNeverInvalidCredentials(String kind, CapturedOutput output) throws Exception {
        RuntimeException fault = switch (kind) {
            case "transient" -> new org.springframework.dao.TransientDataAccessResourceException("secret-canary");
            case "configuration" -> new org.springframework.jdbc.BadSqlGrammarException("operation", "private-query", new java.sql.SQLException("secret-canary", "42000"));
            default -> new IllegalStateException("secret-canary");
        };
        doThrow(fault).when(changes).change(any(), any(), anyString(), anyString());
        var response = change(token, PASSWORD, NEW_PASSWORD);problem(response, kind.equals("transient") ? 503 : 500, kind.equals("transient") ? "SERVICE_UNAVAILABLE" : "SYSTEM_ERROR");
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());assertFalse(response.body().contains("secret-canary"));unchanged();
        for (String value : List.of("secret-canary", "private-query", PASSWORD, NEW_PASSWORD, originalHash, token))
            assertFalse(output.getAll().contains(value), "Failure log leaked authentication data");
    }

    @Test void realUserRowLockTimeoutIs503AndDoesNotMutateCredentials() throws Exception {
        var locked = new CountDownLatch(1);var release = new CountDownLatch(1);var worker = Executors.newSingleThreadExecutor();
        try {
            var holder = worker.submit(() -> new TransactionTemplate(manager).execute(status -> {
                users.findByAuthUidForUpdate(alice.uid()).orElseThrow();locked.countDown();
                try {assertTrue(release.await(12, TimeUnit.SECONDS));} catch (InterruptedException failure) {throw new IllegalStateException(failure);}return null;
            }));
            assertTrue(locked.await(8, TimeUnit.SECONDS));var result = change(token, PASSWORD, NEW_PASSWORD);
            problem(result, 503, "SERVICE_UNAVAILABLE");assertTrue(result.headers().firstValue("Retry-After").isEmpty());
            release.countDown();holder.get(8, TimeUnit.SECONDS);unchanged();
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void maximumGenerationFailsClosedWithoutHashOrAuditChanges() throws Exception {
        jdbc.update("UPDATE users SET token_version=? WHERE id=?", Long.MAX_VALUE, alice.id());
        // A validly signed max-generation token can only be a test fixture; production issuance refuses it.
        var builder = Jwts.builder().subject(alice.uid()).claim("uid", alice.uid()).claim("tokenVersion", Long.toString(Long.MAX_VALUE))
                .issuer("inventory-management-system").issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 3600000));
        String maxToken = builder.signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        problem(change(maxToken, PASSWORD, NEW_PASSWORD), 500, "SYSTEM_ERROR");assertEquals(Long.MAX_VALUE, version());
        assertTrue(originalHash.equals(hash()), "Overflow changed hash");assertEquals(0, audits());assertEquals(0, limiter.inFlightCount());
    }

    @Test void recreatedUsernameCannotBeTargetedWithThePreviousIdentity() throws Exception {
        users.deleteById(alice.id());users.flush();
        users.saveAndFlush(User.builder().username("password_alice").password(originalHash).role("ROLE_USER").build());
        var replacement = TestAuth.principal(jdbc, "password_alice");assertNotEquals(alice.uid(), replacement.uid());
        problem(change(token, PASSWORD, NEW_PASSWORD), 401, "UNAUTHENTICATED");verifyNoInteractions(passwords, changes);
        assertEquals(0, audits());assertEquals(0L, replacement.tokenVersion());
    }
}
