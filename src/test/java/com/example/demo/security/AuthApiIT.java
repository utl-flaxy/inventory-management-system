package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthApiIT {
    private static final String SECRET = Base64.getEncoder()
            .encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String ISSUER = "inventory-management-system";
    private static final String PASSWORD = "auth-api-test-password";

    @Container
    static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("auth_api");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("JWT_ISSUER", () -> ISSUER);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private JwtUtil jwt;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();
    private int clientSequence;

    @BeforeEach
    void cleanStart() {
        clientSequence = CLIENT_SEQUENCE.incrementAndGet(); // Independent callers; default production quotas remain enabled.
        assertEquals(0, users.count(), "Tests must not depend on another test's registered users");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        users.deleteAll();
    }

    @Test
    void registerCreatesUserWithBcryptHashAndNoCredentialInResponse() throws Exception {
        String response = register("alice", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andReturn().getResponse().getContentAsString();
        User user = users.findByUsername("alice").orElseThrow();
        assertEquals("ROLE_USER", user.getRole());
        assertTrue(user.getPassword().startsWith("$2"));
        assertTrue(encoder.matches(PASSWORD, user.getPassword()));
        assertNotEquals(PASSWORD, user.getPassword());
        assertNoCredentials(response, user.getPassword());
        assertEquals(3, json.readTree(response).size(), "Only id/username/role are returned");
        assertEquals(1, users.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "ROLE_ADMIN"})
    void clientRoleInjectionCannotElevateRegisteredUser(String role) throws Exception {
        auth("/auth/register", Map.of("username", "alice", "password", PASSWORD, "role", role))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertEquals("ROLE_USER", users.findByUsername("alice").orElseThrow().getRole());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + loginToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateUsernameKeepsOriginalHashAndReturns409() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String originalHash = users.findByUsername("alice").orElseThrow().getPassword();
        String response = register("alice", "different-password")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail").value("ユーザー名は既に使用されています"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, users.count());
        assertEquals(originalHash, users.findByUsername("alice").orElseThrow().getPassword());
        assertNoCredentials(response, originalHash);
    }

    static Stream<Arguments> nullCredentials() {
        return Stream.of(Arguments.of(null, PASSWORD), Arguments.of("alice", null));
    }

    @ParameterizedTest
    @MethodSource("nullCredentials")
    void nullRegistrationIs400BeforeRepositoryOrJwtWithoutSavingUser(
            String username, String password) throws Exception {
        clearInvocations(users, jwt);
        register(username, password).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + (username == null ? "username" : "password")).isString());
        verifyNoInteractions(users, jwt);
        assertEquals(0, users.count());
    }

    static Stream<Arguments> blankCredentials() {
        return Stream.of(Arguments.of("", PASSWORD), Arguments.of("   ", PASSWORD),
                Arguments.of("alice", ""), Arguments.of("alice", "   "));
    }

    @ParameterizedTest
    @MethodSource("blankCredentials")
    void blankRegistrationIs400BeforeRepositoryOrJwtWithoutSavingUser(
            String username, String password) throws Exception {
        clearInvocations(users, jwt);
        String response = register(username, password).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        verifyNoInteractions(users, jwt);
        assertNoCredentials(response, "unused-hash");
        assertEquals(0, users.count());
    }

    @Test
    void loginReturnsVerifiedJwtWithoutPasswordHashOrSigningSecret() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String response = auth("/auth/login", Map.of("username", "alice", "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        assertEquals(1, body.size(), "Only the token is returned");
        String token = body.get("token").asText();
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
        assertEquals("alice", claims.getSubject());
        assertTrue(claims.getExpiration().after(new Date()));
        assertNotNull(claims.getIssuedAt());
        assertEquals(Set.of("sub", "iss", "iat", "exp"), claims.keySet());
        String decodedPayload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertFalse(response.contains(SECRET));
        assertFalse(decodedPayload.contains(SECRET));
        assertNoCredentials(response, users.findByUsername("alice").orElseThrow().getPassword());
        assertNoCredentials(decodedPayload, users.findByUsername("alice").orElseThrow().getPassword());
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().json("{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}"));
    }

    static Stream<Arguments> invalidLogins() {
        return Stream.of(Arguments.of("alice", "wrong-password", 401),
                Arguments.of("missing", PASSWORD, 401),
                Arguments.of(null, PASSWORD, 400), Arguments.of("", PASSWORD, 400),
                Arguments.of("   ", PASSWORD, 400), Arguments.of("alice", null, 400),
                Arguments.of("alice", "", 400), Arguments.of("alice", "   ", 400));
    }

    @ParameterizedTest
    @MethodSource("invalidLogins")
    void invalidLoginUses400ForInputAnd401ForCredentialsWithoutCredentialLeak(
            String username, String password, int expectedStatus) throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String hash = users.findByUsername("alice").orElseThrow().getPassword();
        clearInvocations(users, jwt);
        String response = auth("/auth/login", credentials(username, password))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        if (expectedStatus == 400) verifyNoInteractions(users, jwt);
        else {
            assertEquals("INVALID_CREDENTIALS", json.readTree(response).path("code").asText());
            assertEquals("ユーザー名またはパスワードが正しくありません", json.readTree(response).path("detail").asText());
            verify(users).findByUsername(username);
            verifyNoMoreInteractions(users);
            verifyNoInteractions(jwt);
        }
        assertNoCredentials(response, hash);
        assertEquals(1, users.count());
        assertEquals(hash, users.findByUsername("alice").orElseThrow().getPassword());
    }

    @Test
    void absentUsernameAndWrongPasswordReturnExactlyIdentical401Bodies() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String wrong = auth("/auth/login", credentials("alice", "valid-wrong-password"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String absent = auth("/auth/login", credentials("missing", "valid-wrong-password"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(wrong, absent);
        var problem = json.readTree(wrong);
        assertEquals("INVALID_CREDENTIALS", problem.path("code").asText());
        assertEquals("ユーザー名またはパスワードが正しくありません", problem.path("detail").asText());
        assertEquals(6, problem.size());
        assertNoCredentials(wrong, users.findByUsername("alice").orElseThrow().getPassword());
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(Arguments.of("abc", "12345678"), Arguments.of("A".repeat(50), "p".repeat(72)),
                Arguments.of("unicode-password", "あ".repeat(24)), Arguments.of("space-password", "  valid password  "));
    }

    @ParameterizedTest
    @MethodSource("validBoundaries")
    void lengthAndUtf8BoundariesAllowRegistrationAndLoginWithoutPasswordNormalization(
            String username, String password) throws Exception {
        register(username, password).andExpect(status().isOk());
        var user = users.findByUsername(username).orElseThrow();
        assertTrue(encoder.matches(password, user.getPassword()));
        String response = auth("/auth/login", credentials(username, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(password));
        assertNoCredentials(response, user.getPassword());
        if (password.startsWith(" ")) auth("/auth/login", credentials(username, password.strip()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUsernameCollationRemainsCaseInsensitiveAndApiLimitDoesNotShrinkDbColumns() throws Exception {
        assertEquals("utf8mb4_unicode_ci", jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='username'", String.class));
        for (String column : List.of("username", "password")) assertEquals(255L,
                jdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME=?", Long.class, column));
        register("Alice", PASSWORD).andExpect(status().isOk());
        register("alice", "different-password").andExpect(status().isConflict());
        String response = auth("/auth/login", credentials("ALICE", PASSWORD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("Alice", jwt.extractUsername(json.readTree(response).path("token").asText()));
        assertEquals(1, users.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", ""})
    void malformedJsonNullJsonOrMissingBodyAre400ForBothAuthEndpoints(String body) throws Exception {
        clearInvocations(users, jwt);
        for (String path : List.of("/auth/register", "/auth/login")) mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(users, jwt);
        assertEquals(0, users.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/register", "/auth/login"})
    void unsupportedContentTypeIs415BeforeAuthenticationProcessing(String path) throws Exception {
        clearInvocations(users, jwt);
        mvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("private-invalid-body"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(users, jwt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/register", "/auth/login"})
    void utf8PasswordOver72BytesIs400WithoutRepositoryQueriesEvenWhenCharCountIsBelow72(String path) throws Exception {
        clearInvocations(users, jwt);
        auth(path, credentials("alice", "あ".repeat(24) + "a")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("パスワードはUTF-8で72 bytes以内で入力してください"));
        verifyNoInteractions(users, jwt);
        assertEquals(0, users.count());
    }

    @Test
    void actualUsernameUniqueViolationIs409WithOriginalHashUnchangedAndNoRawSqlLeak() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String before = jdbc.queryForObject("SELECT password FROM users WHERE username='alice'", String.class);
        // 両findが未登録と判断するraceを模擬し、実MariaDB UNIQUE違反の識別を必ず通す。
        doReturn(java.util.Optional.empty()).when(users).findByUsername("alice");
        String response = register("alice", "different-password").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
                .andReturn().getResponse().getContentAsString();
        assertNoCredentials(response, before);
        assertFalse(response.contains("Duplicate entry"));
        assertFalse(response.contains("uk_users_username"));
        assertEquals(before, jdbc.queryForObject("SELECT password FROM users WHERE username='alice'", String.class));
        assertEquals(1, users.count());
    }

    @Test
    void openApiReflectsAuthValidationResponseCodesAndRegisterDtoWithoutPassword() throws Exception {
        var document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var schemas = document.path("components").path("schemas");
        for (String schema : List.of("RegisterRequest", "LoginRequest")) {
            var properties = schemas.path(schema).path("properties");
            assertEquals(3, properties.path("username").path("minLength").asInt());
            assertEquals(50, properties.path("username").path("maxLength").asInt());
            assertEquals("[A-Za-z0-9._-]{3,50}", properties.path("username").path("pattern").asText());
            assertEquals(8, properties.path("password").path("minLength").asInt());
            assertEquals(72, properties.path("password").path("maxLength").asInt());
            assertTrue(properties.path("password").path("writeOnly").asBoolean());
            assertTrue(properties.path("password").path("description").asText().contains("bytes"));
        }
        assertEquals(3, schemas.path("RegisterResponse").path("properties").size());
        assertFalse(schemas.path("RegisterResponse").path("properties").has("password"));
        for (String code : List.of("200", "400", "409", "429", "503")) assertTrue(document.path("paths").path("/auth/register").path("post").path("responses").has(code));
        var registration = document.path("paths").path("/auth/register").path("post");
        assertTrue(registration.path("description").asText().contains("5 admitted attempts"));
        assertTrue(registration.path("responses").path("503").path("description").asText().contains("REGISTRATION_SOURCE_UNAVAILABLE"));
        assertEquals("#/components/schemas/ApiProblem", registration.path("responses").path("429")
                .path("content").path("application/problem+json").path("schema").path("$ref").asText());
        assertEquals(1, registration.path("responses").path("429").path("headers").path("Retry-After").path("schema").path("minimum").asInt());
        for (String code : List.of("200", "400", "401", "429", "503")) assertTrue(document.path("paths").path("/auth/login").path("post").path("responses").has(code));
        var unavailable = document.path("paths").path("/auth/login").path("post").path("responses").path("503");
        assertTrue(unavailable.path("description").asText().contains("LOGIN_SOURCE_UNAVAILABLE"));
        assertEquals("#/components/schemas/ApiProblem",
                unavailable.path("content").path("application/problem+json").path("schema").path("$ref").asText());
        var limited = document.path("paths").path("/auth/login").path("post").path("responses").path("429");
        assertTrue(limited.path("content").has("application/problem+json"));
        assertEquals(1, limited.path("headers").path("Retry-After").path("schema").path("minimum").asInt());
    }

    @Test
    @Timeout(120)
    void tenSameUsernameRegistrationsHaveOneSuccessAndNineConflicts() throws Exception {
        var requests = java.util.Collections.nCopies(10, new Registration("race-user", PASSWORD));
        assertSingleWinner(requests, concurrentRegistration(requests));
    }

    @Test
    @Timeout(120)
    void concurrentDifferentPasswordsForSameUsernameStoreOnlyTheWinningPassword() throws Exception {
        List<Registration> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) requests.add(new Registration("race-password", i % 2 == 0 ? PASSWORD : "another-valid-password"));
        assertSingleWinner(requests, concurrentRegistration(requests));
    }

    @Test
    @Timeout(120)
    void tenDifferentUsernamesRegisterConcurrentlyWithoutFalseConflicts() throws Exception {
        List<Registration> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) requests.add(new Registration("distinct-" + i, PASSWORD));
        var results = concurrentRegistration(requests);
        for (var result : results) assertEquals(200, result.status, result.body);
        assertEquals(10, users.count());
        for (var request : requests) assertTrue(encoder.matches(request.password, users.findByUsername(request.username).orElseThrow().getPassword()));
    }

    private void assertSingleWinner(List<Registration> requests, List<Outcome> outcomes) throws Exception {
        assertEquals(1, outcomes.stream().filter(outcome -> outcome.status == 200).count());
        assertEquals(9, outcomes.stream().filter(outcome -> outcome.status == 409).count());
        assertEquals(1, users.count());
        var user = users.findAll().get(0);
        assertEquals("ROLE_USER", user.getRole());
        assertTrue(user.getPassword().startsWith("$2"));
        for (int i = 0; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            assertNoCredentials(outcome.body, user.getPassword());
            assertFalse(outcome.body.contains("another-valid-password"));
            if (outcome.status == 200) assertTrue(encoder.matches(requests.get(i).password, user.getPassword()));
            else assertEquals("USERNAME_ALREADY_EXISTS", json.readTree(outcome.body).path("code").asText());
        }
    }

    private List<Outcome> concurrentRegistration(List<Registration> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size()), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        // Ten callers still start together; exercise DB uniqueness within the unchanged default work bound.
        // HTTP overload rejection itself is tested with simultaneous requests in RegistrationSecurityIT.
        var admission = new Semaphore(2);
        try {
            for (var request : requests) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                assertTrue(admission.tryAcquire(10, TimeUnit.SECONDS));
                try {
                    var response = register(request.username, request.password).andReturn().getResponse();
                    return new Outcome(response.getStatus(), response.getContentAsString());
                } finally { admission.release(); }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record Registration(String username, String password) { }
    private record Outcome(int status, String body) { }

    @Test
    void currentDatabaseRoleOverridesJwtRoleClaimAndChangesWithoutReissuingToken() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String token = Jwts.builder().subject("alice").issuer(ISSUER)
                .claim("role", "ROLE_ADMIN").issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        User user = users.findByUsername("alice").orElseThrow();
        user.setRole("ROLE_ADMIN");
        users.saveAndFlush(user);
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        user.setRole("ROLE_USER");
        users.saveAndFlush(user);
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginTokenRequiresExistingDatabaseUser() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String token = loginToken();
        users.deleteAll();
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String loginToken() throws Exception {
        String response = auth("/auth/login", Map.of("username", "alice", "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asText();
    }

    private ResultActions register(String username, String password) throws Exception {
        return auth("/auth/register", credentials(username, password));
    }

    private Map<String, Object> credentials(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    private ResultActions auth(String endpoint, Map<String, ?> body) throws Exception {
        String source = endpoint.equals("/auth/register") ? com.example.demo.TestAuthRequests.nextClientAddress()
                : "198.51.100." + clientSequence;
        return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr(source); return request; })
                .content(json.writeValueAsString(body)));
    }

    private void assertNoCredentials(String response, String hash) throws Exception {
        JsonNode body = json.readTree(response);
        // Validationのfield名passwordは許容するが、実入力・hash・secretを返さない。
        assertFalse(body.has("hash"));
        assertFalse(response.contains(PASSWORD));
        assertFalse(response.contains(hash));
        assertFalse(response.contains(SECRET));
        assertFalse(response.contains("rawPassword"));
        assertFalse(response.contains("$2"));
    }
}
