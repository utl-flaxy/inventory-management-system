package com.example.demo.security;

import com.example.demo.TestApplicationLogs;
import com.example.demo.controller.SelfOrderController;
import com.example.demo.service.OrderService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(SelfOrderController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfig.class, JwtUtil.class, SecurityProblemWriter.class, com.example.demo.config.ClockConfiguration.class})
class JwtDatabaseFailureTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String CANARY = "private-db-failure-canary";
    @Autowired MockMvc mvc;
    @MockitoSpyBean JwtUtil jwt;
    @MockitoBean JwtUserAuthenticator users;
    @MockitoBean UserDetailsService unusedStandardUserDetails;
    @MockitoBean OrderService orders;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("JWT_SECRET", () -> SECRET); }

    @Test
    void deletedUserKeepsUnauthenticated401() throws Exception {
        String uid = java.util.UUID.randomUUID().toString();
        String token = jwt.generateToken(uid, 0);
        when(users.authenticate(new JwtUtil.VerifiedJwt(uid, 0)))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException(CANARY));
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(problem(401, "UNAUTHENTICATED", "Unauthenticated", "/api/v1/me/orders"));
        verifyNoInteractions(orders);
    }

    @Test
    void unexpectedParserFailureIs500RatherThanAnInvalidToken() throws Exception {
        String token = jwt.generateToken(java.util.UUID.randomUUID().toString(), 0);
        doThrow(new IllegalStateException(CANARY)).when(jwt).verify(token);
        mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/api/v1/me/orders"));
        verifyNoInteractions(users, orders);
    }

    @ParameterizedTest
    @MethodSource("com.example.demo.exception.ServerFailureResponseTest#failures")
    void validTokenDoesNotTurnServerFailureInto401(RuntimeException failure, int status, String code, String title) throws Exception {
        String username = java.util.UUID.randomUUID().toString();
        String token = jwt.generateToken(username, 0);
        when(users.authenticate(new JwtUtil.VerifiedJwt(username, 0))).thenThrow(failure);
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                    .andExpect(problem(status, code, title, "/api/v1/me/orders"));
            verifyNoInteractions(orders);
            assertTrue(logs.events("security.jwt.rejected").isEmpty());
            var event = logs.events(status == 503 ? "system.database_unavailable" : "system.unhandled_error");
            assertEquals(1, event.size());
            assertEquals(status == 503 ? ch.qos.logback.classic.Level.WARN : ch.qos.logback.classic.Level.ERROR,
                    event.get(0).getLevel());
            assertNotNull(event.get(0).getMDCPropertyMap().get("requestId"));
            for (var entry : logs.events()) {
                assertNull(entry.getThrowableProxy());
                String rendered = entry.getFormattedMessage() + TestApplicationLogs.fields(entry) + entry.getMDCPropertyMap();
                for (String secret : java.util.List.of(CANARY, username, token, SECRET, "jdbc:"))
                    assertFalse(rendered.contains(secret), "Sensitive diagnostic value leaked");
            }
        }
    }
}
