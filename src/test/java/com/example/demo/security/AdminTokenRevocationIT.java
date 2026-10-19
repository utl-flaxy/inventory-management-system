package com.example.demo.security;

import com.example.demo.TestAuth;
import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthRevocationTransactions;
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
class AdminTokenRevocationIT {
    private static String random() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
    private static final String SECRET = random(), PASSWORD = random(), NEW_PASSWORD = random();
    private static final AtomicInteger SOURCES = new AtomicInteger();
    private static final String AUDIT_SQL = "INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES (?,?,'FORCE_REVOKE','SUCCEEDED',?,?)";
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("auth_force_b3").withUsername("inventory_app").withPassword(random())
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
    @MockitoSpyBean com.example.demo.service.AdminTokenRevocationTransactions forced;
    @Autowired com.example.demo.service.AdminTokenRevocationService service;
    @Autowired AuthRevocationTransactions revocations;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired JwtUtil jwt;
    @Autowired AuthMutationRateLimiter limiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private AuthPrincipal alice, bob, admin, secondAdmin;
    private String token, bobToken, adminToken, originalHash;

    @BeforeEach void fixture() {
        for (String table : List.of("auth_revocation_audit", "inventory_movements", "order_items", "orders", "products", "users"))
            jdbc.update("DELETE FROM " + table); // Only the disposable database created above.
        originalHash = passwords.encode(PASSWORD);
        for (String name : List.of("password_alice", "password_bob", "password_admin", "second_admin"))
            users.saveAndFlush(User.builder().username(name).password(originalHash).role(name.endsWith("admin") ? "ROLE_ADMIN" : "ROLE_USER").build());
        alice = TestAuth.principal(jdbc, "password_alice");bob = TestAuth.principal(jdbc, "password_bob");admin = TestAuth.principal(jdbc, "password_admin");
        secondAdmin = TestAuth.principal(jdbc, "second_admin");
        token = jwt.generateToken(alice.uid(), 0);bobToken = jwt.generateToken(bob.uid(), 0);adminToken = jwt.generateToken(admin.uid(), 0);
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'password-business-canary',100,10,0)");
        clearInvocations(passwords, users, forced, authenticator);
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
    private com.example.demo.service.AdminTokenRevocationTransactions.Proof proof(AuthPrincipal actor) {
        return new com.example.demo.service.AdminTokenRevocationTransactions.Proof(actor.id(), actor.uid(), actor.tokenVersion(), originalHash);
    }
    private HttpResponse<String> revoke(String bearer, String uid, String password) throws Exception {
        return send("POST", path(uid), bearer, Map.of("currentPassword", password), nextSource());
    }
    private String path(String uid) { return "/api/v1/admin/users/" + uid + "/token-revocations"; }
    private long generation(AuthPrincipal who) { return jdbc.queryForObject("SELECT token_version FROM users WHERE id=?", Long.class, who.id()); }
    private void noForceAudit() { assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_audit WHERE action='FORCE_REVOKE'", Integer.class)); }

    @Test void adminReauthenticatesOnceOutsideTransactionAndRevokesEveryTargetDevice(CapturedOutput output) throws Exception {
        String second = new JwtUtil(SECRET, "inventory-management-system", 3600000,
                java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofMinutes(-1))).generateToken(alice.uid(), 0);
        assertFalse(token.equals(second));
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return call.callRealMethod(); })
                .when(passwords).matches(PASSWORD, originalHash);
        var result = revoke(adminToken, alice.uid(), PASSWORD);
        assertEquals(204, result.statusCode());assertTrue(result.body().isEmpty());
        verify(passwords, times(1)).matches(PASSWORD, originalHash);verify(passwords, never()).encode(anyString());
        assertEquals(1, version());assertTrue(originalHash.equals(hash()));assertEquals(1, audits());
        var audit = jdbc.queryForMap("SELECT actor_user_id,target_user_id,action,result,request_id FROM auth_revocation_audit");
        assertEquals(admin.id(), ((Number) audit.get("actor_user_id")).longValue());
        assertEquals(alice.id(), ((Number) audit.get("target_user_id")).longValue());
        assertEquals("FORCE_REVOKE", audit.get("action"));assertEquals("SUCCEEDED", audit.get("result"));
        assertEquals(result.headers().firstValue("X-Request-Id").orElseThrow(), audit.get("request_id"));
        problem(get(token), 401, "UNAUTHENTICATED");problem(get(second), 401, "UNAUTHENTICATED");
        assertEquals(200, get(bobToken).statusCode());assertEquals(200, get(adminToken).statusCode());
        assertEquals(200, login("password_alice", PASSWORD).statusCode());
        assertEquals(204, revoke(adminToken, alice.uid(), PASSWORD).statusCode());assertEquals(2, version());
        for (String secret : List.of(SECRET, PASSWORD, originalHash, token, second, adminToken, "password_admin", "198.19."))
            assertFalse(output.getAll().contains(secret), "Authentication data leaked to logs");
        assertTrue(output.getAll().contains("auth.force_revoke.succeeded"));
    }

    @Test void selfRevocationLocksOneRowAndOldAdminCannotReplay() throws Exception {
        assertEquals(204, revoke(adminToken, admin.uid(), PASSWORD).statusCode());
        verify(users, times(1)).findByIdForUpdate(admin.id());assertEquals(1, generation(admin));assertEquals(1, audits());
        problem(revoke(adminToken, admin.uid(), PASSWORD), 401, "UNAUTHENTICATED");
        assertEquals(200, get(token).statusCode());assertEquals(200, login("password_admin", PASSWORD).statusCode());
    }

    @Test void userAndAnonymousAreRejectedBeforeTargetLookupOrPasswordWork() throws Exception {
        String absent = UUID.randomUUID().toString();
        problem(revoke(token, absent, PASSWORD), 403, "ACCESS_DENIED");
        problem(revoke(null, absent, PASSWORD), 401, "UNAUTHENTICATED");
        verify(users, never()).findByAuthUid(absent);verifyNoInteractions(passwords, forced);unchanged();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(JwtNegativeTokens.Case.class)
    void allSixInvalidJwtVectorsAreRejectedBeforeAdminProofOrTargetMutation(JwtNegativeTokens.Case vector) throws Exception {
        String invalid = JwtNegativeTokens.invalid(vector, SECRET, random(), alice.uid(), admin.uid());
        problem(revoke(invalid, alice.uid(), PASSWORD), 401, "UNAUTHENTICATED");
        verifyNoInteractions(authenticator, passwords, forced);unchanged();
    }

    @Test void rolePromotionWithGenerationRequiresNewJwtAndRoleDemotionCannotForceRevoke() throws Exception {
        jdbc.update("UPDATE users SET role='ROLE_ADMIN',token_version=token_version+1 WHERE id=?", bob.id());
        problem(revoke(bobToken, alice.uid(), PASSWORD), 401, "UNAUTHENTICATED");
        String promoted = jwt.generateToken(bob.uid(), 1);
        assertEquals(204, revoke(promoted, alice.uid(), PASSWORD).statusCode());
        jdbc.update("UPDATE users SET role='ROLE_USER',token_version=token_version+1 WHERE id=?", bob.id());
        problem(revoke(promoted, alice.uid(), PASSWORD), 401, "UNAUTHENTICATED");
        problem(revoke(jwt.generateToken(bob.uid(), 2), alice.uid(), PASSWORD), 403, "ACCESS_DENIED");
        assertEquals(1, version());assertEquals(1, audits());
    }

    @Test void bodyCannotSelectAnotherTargetOrChangeRoles() throws Exception {
        assertEquals(204, send("POST", path(alice.uid()), adminToken,
                Map.of("currentPassword", PASSWORD, "uid", bob.uid(), "username", "password_bob", "role", "ROLE_ADMIN"), nextSource()).statusCode());
        assertEquals(1, version());assertEquals(0, generation(bob));
        assertEquals("ROLE_USER", jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, alice.id()));
    }

    @Test void wrongOwnPasswordDoesNotResolveTargetAndCannotUseTargetsPassword() throws Exception {
        jdbc.update("UPDATE users SET password=? WHERE id=?", passwords.encode(NEW_PASSWORD), alice.id());
        problem(revoke(adminToken, alice.uid(), NEW_PASSWORD), 401, "INVALID_CREDENTIALS");
        verify(users, never()).findByAuthUid(alice.uid());verifyNoInteractions(forced);noForceAudit();assertEquals(0, version());
    }

    @Test void absentTargetIs404OnlyAfterValidAdminProof() throws Exception {
        problem(revoke(adminToken, UUID.randomUUID().toString(), PASSWORD), 404, "USER_NOT_FOUND");unchanged();
    }

    @ParameterizedTest @ValueSource(strings = {"bad-uid", "00000000-0000-0000-0000-000000000000", "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF"})
    void malformedTargetIsSafe400(String uid) throws Exception {
        problem(revoke(adminToken, uid, PASSWORD), 400, "INVALID_REQUEST");verifyNoInteractions(passwords, forced);unchanged();
    }

    @ParameterizedTest @ValueSource(strings = {"", "short", "                                                                        ", "日本語日本語日本語日本語日本語日本語日本語日本語日"})
    void invalidCurrentPasswordIs400WithoutBcrypt(String password) throws Exception {
        var response = revoke(adminToken, alice.uid(), password);problem(response, 400, "VALIDATION_ERROR");
        assertTrue(json.readTree(response.body()).path("errors").has("currentPassword"));verifyNoInteractions(passwords, forced);unchanged();
    }

    @Test void legacyEightCharacterAdminCredentialRemainsAccepted() throws Exception {
        String legacy = "legacy12";jdbc.update("UPDATE users SET password=? WHERE id=?", passwords.encode(legacy), admin.id());
        assertEquals(204, revoke(adminToken, alice.uid(), legacy).statusCode());assertEquals(1, version());
    }

    @Test void sourceWindowsAreIndependentButAllThreeOperationsSharePermits() throws Exception {
        String source = nextSource();
        for (int i = 0; i < 5; i++) problem(send("POST", path(alice.uid()), adminToken, Map.of("currentPassword", "wrong-password"), source), 401, "INVALID_CREDENTIALS");
        var limited = send("POST", path(alice.uid()), adminToken, Map.of("currentPassword", PASSWORD), source);
        problem(limited, 429, "AUTH_MUTATION_RATE_LIMITED");assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        problem(send("POST", "/auth/password", adminToken, Map.of("currentPassword", "wrong-password", "newPassword", NEW_PASSWORD), source), 401, "INVALID_CREDENTIALS");
        assertEquals(204, revoke(adminToken, alice.uid(), PASSWORD).statusCode());
        assertEquals(204, send("POST", "/auth/logout-all", adminToken, null, source).statusCode());
        try (var one = limiter.acquire(AuthMutationRateLimiter.Operation.LOGOUT_ALL, nextSource());
             var two = limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, nextSource())) {
            clearInvocations(passwords, forced);
            problem(revoke(jwt.generateToken(secondAdmin.uid(), 0), bob.uid(), PASSWORD), 429, "AUTH_MUTATION_RATE_LIMITED");
            verifyNoInteractions(passwords, forced);
        }
        assertEquals(0, limiter.inFlightCount());
    }

    @Test void ambiguousProxyFailsClosedBeforeCredentialsAndHasNoRetryHeader() throws Exception {
        var result = send("POST", path(alice.uid()), adminToken, Map.of("currentPassword", PASSWORD), "203.0.113.1, 203.0.113.2");
        problem(result, 503, "AUTH_MUTATION_SOURCE_UNAVAILABLE");assertTrue(result.headers().firstValue("Retry-After").isEmpty());
        verifyNoInteractions(passwords, forced);unchanged();
    }

    @Test void independentAdminRequestsEachIncrementFreshTargetGenerationOnDistinctConnections() throws Exception { race(false, false); }
    @Test void crossAdminRequestsUseAscendingLocksAndOnlyTheStillValidActorCommits() throws Exception { race(true, false); }
    @Test void simultaneousSelfRevocationsHaveExactlyOneCommit() throws Exception { race(false, true); }
    private void race(boolean cross, boolean self) throws Exception {
        var entered = new CountDownLatch(2);var release = new CountDownLatch(1);var lockEntered = new CountDownLatch(2);
        var connections = ConcurrentHashMap.<Long>newKeySet();var orders = new ConcurrentHashMap<Long, List<Long>>();
        doAnswer(call -> {var result = call.callRealMethod();entered.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return result;})
                .when(passwords).matches(PASSWORD, originalHash);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);connections.add(connection);
            var ids = orders.computeIfAbsent(connection, ignored -> new ArrayList<>());ids.add(call.getArgument(0));
            if (ids.size() == 1) {lockEntered.countDown();assertTrue(lockEntered.await(8, TimeUnit.SECONDS));}
            return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(users).findByIdForUpdate(anyLong());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> revoke(adminToken, cross ? secondAdmin.uid() : self ? admin.uid() : alice.uid(), PASSWORD));
            var b = pool.submit(() -> revoke(!self ? jwt.generateToken(secondAdmin.uid(), 0) : adminToken,
                    cross || self ? admin.uid() : alice.uid(), PASSWORD));
            assertTrue(entered.await(8, TimeUnit.SECONDS));release.countDown();var x = a.get(12, TimeUnit.SECONDS);var y = b.get(12, TimeUnit.SECONDS);
            if (!cross && !self) {assertEquals(204, x.statusCode());assertEquals(204, y.statusCode());assertEquals(2, version());assertEquals(2, audits());}
            else {assertEquals(1, (x.statusCode() == 204 ? 1 : 0) + (y.statusCode() == 204 ? 1 : 0));
                problem(x.statusCode() == 401 ? x : y, 401, "UNAUTHENTICATED");assertEquals(1, audits());
                assertEquals(1, generation(admin) + (cross ? generation(secondAdmin) : 0));}
            assertEquals(2, connections.size());
            for (var ids : orders.values()) {assertEquals(ids.stream().distinct().sorted().toList(), ids);assertEquals(self ? 1 : 2, ids.size());}
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(strings = {"logout", "password", "demote-version", "demote-only", "hash-only", "delete", "target-recreate", "target-logout", "target-password"})
    void changesAfterProofCannotReuseStaleActorOrRetargetRecreatedUsername(String mutation) throws Exception {
        var verified = new CountDownLatch(1);var release = new CountDownLatch(1);
        doAnswer(call -> {var result = call.callRealMethod();verified.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return result;})
                .when(passwords).matches(PASSWORD, originalHash);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var request = worker.submit(() -> revoke(adminToken, alice.uid(), PASSWORD));assertTrue(verified.await(8, TimeUnit.SECONDS));
            switch (mutation) {
                case "logout" -> revocations.logoutAll(admin, UUID.randomUUID().toString());
                case "password" -> changes.change(admin, new PasswordChangeTransactions.Snapshot(admin.id(), admin.uid(), 0, originalHash, "ROLE_ADMIN"), passwords.encode(NEW_PASSWORD), UUID.randomUUID().toString());
                case "demote-version" -> jdbc.update("UPDATE users SET role='ROLE_USER', token_version=token_version+1 WHERE id=?", admin.id());
                case "demote-only" -> jdbc.update("UPDATE users SET role='ROLE_USER' WHERE id=?", admin.id());
                case "hash-only" -> jdbc.update("UPDATE users SET password=? WHERE id=?", passwords.encode(NEW_PASSWORD), admin.id());
                case "delete" -> { users.deleteById(admin.id());users.flush(); }
                case "target-recreate" -> {users.deleteById(alice.id());users.flush();users.saveAndFlush(User.builder().username("password_alice").password(originalHash).role("ROLE_USER").build());}
                case "target-logout" -> revocations.logoutAll(alice, UUID.randomUUID().toString());
                case "target-password" -> changes.change(alice, new PasswordChangeTransactions.Snapshot(alice.id(), alice.uid(), 0, originalHash, "ROLE_USER"), passwords.encode(NEW_PASSWORD), UUID.randomUUID().toString());
                default -> fail("Unknown mutation");
            }
            release.countDown();var result = request.get(12, TimeUnit.SECONDS);
            if (mutation.startsWith("target-") && !mutation.equals("target-recreate")) {
                assertEquals(204, result.statusCode());assertEquals(2, version());assertEquals(2, audits());
            } else {
                problem(result, mutation.equals("demote-only") ? 403 : mutation.equals("target-recreate") ? 404 : 401,
                        mutation.equals("demote-only") ? "ACCESS_DENIED" : mutation.equals("target-recreate") ? "USER_NOT_FOUND" : "UNAUTHENTICATED");noForceAudit();
                if (mutation.equals("target-recreate")) assertEquals(0, TestAuth.principal(jdbc, "password_alice").tokenVersion());else assertEquals(0, version());
            }
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void auditFailureRollsBackGenerationAndNeverLogsSuccess(CapturedOutput output) throws Exception {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Injected audit rejection"))
                .when(jdbc).update(eq(AUDIT_SQL), eq(admin.id()), eq(alice.id()), any(Timestamp.class), anyString());
        problem(revoke(adminToken, alice.uid(), PASSWORD), 500, "SYSTEM_ERROR");unchanged();
        assertEquals(200, get(token).statusCode());assertFalse(output.getAll().contains("auth.force_revoke.succeeded"));
    }

    @ParameterizedTest @ValueSource(strings = {"commit", "rollback"})
    void oldJwtRemainsValidUntilCommitAndRollbackLeavesNoRevocation(String action) throws Exception {
        var updated = new CountDownLatch(1);var release = new CountDownLatch(1);var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> new TransactionTemplate(manager).execute(status -> {
                forced.revoke(admin, proof(admin), alice.uid(), UUID.randomUUID().toString());updated.countDown();
                try {assertTrue(release.await(8, TimeUnit.SECONDS));} catch (InterruptedException failure) {throw new IllegalStateException(failure);}
                if (action.equals("rollback")) status.setRollbackOnly();return null;
            }));
            assertTrue(updated.await(8, TimeUnit.SECONDS));assertEquals(200, get(token).statusCode());assertEquals(0, version());assertEquals(0, audits());
            release.countDown();pending.get(8, TimeUnit.SECONDS);
            if (action.equals("commit")) {problem(get(token), 401, "UNAUTHENTICATED");assertEquals(1, version());assertEquals(1, audits());}
            else unchanged();
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(strings = {"transient", "unknown", "configuration"})
    void failuresKeepExisting503500ClassificationAndNeverLeakPrivateData(String kind, CapturedOutput output) throws Exception {
        RuntimeException fault = switch (kind) {
            case "transient" -> new org.springframework.dao.TransientDataAccessResourceException("private-canary");
            case "configuration" -> new org.springframework.jdbc.BadSqlGrammarException("operation", "private-query", new java.sql.SQLException("private-canary", "42000"));
            default -> new IllegalStateException("private-canary");
        };
        doThrow(fault).when(forced).revoke(any(), any(), anyString(), anyString());
        var result = revoke(adminToken, alice.uid(), PASSWORD);
        problem(result, kind.equals("transient") ? 503 : 500, kind.equals("transient") ? "SERVICE_UNAVAILABLE" : "SYSTEM_ERROR");
        assertTrue(result.headers().firstValue("Retry-After").isEmpty());unchanged();
        for (String value : List.of("private-canary", "private-query", PASSWORD, originalHash, adminToken)) assertFalse(output.getAll().contains(value));
    }

    @Test void realTargetLockTimeoutIs503WithoutRetryOrPartialChanges() throws Exception {
        var locked = new CountDownLatch(1);var release = new CountDownLatch(1);var worker = Executors.newSingleThreadExecutor();
        try {
            var holder = worker.submit(() -> new TransactionTemplate(manager).execute(status -> {
                users.findByIdForUpdate(alice.id()).orElseThrow();locked.countDown();
                try {assertTrue(release.await(12, TimeUnit.SECONDS));} catch (InterruptedException failure) {throw new IllegalStateException(failure);}return null;
            }));
            assertTrue(locked.await(8, TimeUnit.SECONDS));var response = revoke(adminToken, alice.uid(), PASSWORD);
            problem(response, 503, "SERVICE_UNAVAILABLE");assertTrue(response.headers().firstValue("Retry-After").isEmpty());
            release.countDown();holder.get(8, TimeUnit.SECONDS);unchanged();
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void exhaustedTargetGenerationCannotWrapOrWriteAudit() throws Exception {
        jdbc.update("UPDATE users SET token_version=? WHERE id=?", Long.MAX_VALUE, alice.id());
        problem(revoke(adminToken, alice.uid(), PASSWORD), 500, "SYSTEM_ERROR");assertEquals(Long.MAX_VALUE, version());noForceAudit();assertTrue(originalHash.equals(hash()));
    }

    @ParameterizedTest @ValueSource(strings = {"logout", "password"})
    void targetMutationAuthenticatedBeforeForcedCommitCannotCommitStaleGeneration(String operation) throws Exception {
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);
        if (operation.equals("logout")) {
            doAnswer(call -> {var result = call.callRealMethod();entered.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return result;})
                    .when(authenticator).authenticate(new JwtUtil.VerifiedJwt(alice.uid(), 0));
        } else {
            doAnswer(call -> {entered.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));return call.callRealMethod();})
                    .when(changes).change(eq(alice), any(), anyString(), anyString());
        }
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> operation.equals("logout")
                    ? send("POST", "/auth/logout-all", token, null, nextSource()) : change(token, PASSWORD, NEW_PASSWORD));
            assertTrue(entered.await(8, TimeUnit.SECONDS));assertEquals(204, revoke(adminToken, alice.uid(), PASSWORD).statusCode());
            release.countDown();problem(pending.get(12, TimeUnit.SECONDS), 401, "UNAUTHENTICATED");
            assertEquals(1, version());assertEquals(1, audits());assertTrue(originalHash.equals(hash()));
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }

    @Test void bcryptRejectsAmbientTransactionBeforeAnyCredentialLookup() {
        var request = new com.example.demo.dto.AdminTokenRevocationRequest();request.setCurrentPassword(PASSWORD);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> new TransactionTemplate(manager).execute(status -> {
            service.revoke(admin, alice.uid(), request, UUID.randomUUID().toString());return null;
        }));verifyNoInteractions(passwords, forced);unchanged();
    }

    @Test void targetDeletedAndInternalIdReusedAfterResolutionCannotRevokeTheReplacement() throws Exception {
        var resolving = new CountDownLatch(1);var release = new CountDownLatch(1);var worker = Executors.newSingleThreadExecutor();
        doAnswer(call -> {resolving.countDown();assertTrue(release.await(8, TimeUnit.SECONDS));
            return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(users).findByIdForUpdate(alice.id());
        try {
            var pending = worker.submit(() -> revoke(adminToken, alice.uid(), PASSWORD));assertTrue(resolving.await(8, TimeUnit.SECONDS));
            jdbc.update("DELETE FROM users WHERE id=?", alice.id());
            String newUid = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO users(id,username,password,role,auth_uid,token_version) VALUES(?,'password_alice',?,'ROLE_USER',?,0)", alice.id(), originalHash, newUid);
            release.countDown();problem(pending.get(12, TimeUnit.SECONDS), 404, "USER_NOT_FOUND");
            assertEquals(0, jdbc.queryForObject("SELECT token_version FROM users WHERE auth_uid=?", Long.class, newUid));noForceAudit();
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));}
    }
}
