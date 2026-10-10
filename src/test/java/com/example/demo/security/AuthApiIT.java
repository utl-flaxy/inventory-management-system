package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
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
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void cleanStart() {
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
    void duplicateUsernameKeepsOriginalHashAndCurrentErrorContract() throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String originalHash = users.findByUsername("alice").orElseThrow().getPassword();
        String response = register("alice", "different-password")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.message").value("ユーザー名は既に使用されています"))
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
    void nullRegistrationCurrentlyFailsWithoutSavingUserPendingSeparateValidationIssue(
            String username, String password) throws Exception {
        register(username, password).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertEquals(0, users.count());
    }

    static Stream<Arguments> blankCredentials() {
        return Stream.of(Arguments.of("", PASSWORD), Arguments.of("   ", PASSWORD),
                Arguments.of("alice", ""), Arguments.of("alice", "   "));
    }

    @ParameterizedTest
    @MethodSource("blankCredentials")
    void blankRegistrationIsCurrentlyAcceptedPendingSeparateValidationIssue(
            String username, String password) throws Exception {
        String response = register(username, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        User user = users.findByUsername(username).orElseThrow();
        assertTrue(encoder.matches(password, user.getPassword()));
        assertEquals("ROLE_USER", user.getRole());
        assertNoCredentials(response, user.getPassword());
        assertEquals(1, users.count());
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
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    static Stream<Arguments> invalidLogins() {
        return Stream.of(Arguments.of("alice", "wrong-password", "パスワードが違います"),
                Arguments.of("missing", PASSWORD, "ユーザーが存在しません"),
                Arguments.of(null, PASSWORD, "ユーザーが存在しません"),
                Arguments.of("", PASSWORD, "ユーザーが存在しません"),
                Arguments.of("   ", PASSWORD, "ユーザーが存在しません"),
                Arguments.of("alice", null, "rawPassword cannot be null"),
                Arguments.of("alice", "", "パスワードが違います"),
                Arguments.of("alice", "   ", "パスワードが違います"));
    }

    @ParameterizedTest
    @MethodSource("invalidLogins")
    void invalidLoginKeepsCurrentErrorContractAndDoesNotReturnCredentials(
            String username, String password, String message) throws Exception {
        register("alice", PASSWORD).andExpect(status().isOk());
        String hash = users.findByUsername("alice").orElseThrow().getPassword();
        String response = auth("/auth/login", credentials(username, password))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertNoCredentials(response, hash);
        assertEquals(1, users.count());
        assertEquals(hash, users.findByUsername("alice").orElseThrow().getPassword());
    }

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
        return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private void assertNoCredentials(String response, String hash) throws Exception {
        JsonNode body = json.readTree(response);
        assertFalse(body.has("password"));
        assertFalse(body.has("hash"));
        assertFalse(response.contains(PASSWORD));
        assertFalse(response.contains(hash));
    }
}
