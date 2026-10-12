package com.example.demo.security;

import com.example.demo.TestApplicationLogs;
import com.example.demo.controller.SelfOrderController;
import com.example.demo.service.OrderService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SelfOrderController.class)
@Import({SecurityConfig.class, JwtUtil.class, SecurityProblemWriter.class})
class JwtAuthenticationTest {

    private static final String ISSUER = "inventory-management-system";
    private static final String SECRET = newSecret();

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("JWT_ISSUER", () -> ISSUER);
    }

    private static String newSecret() {
        return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    }

    private String token(String secret, String issuer, Date expiration) {
        return Jwts.builder()
                .subject("alice")
                .issuer(issuer)
                .expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void unauthenticatedProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/orders")).andExpect(status().isUnauthorized());

        verify(jwtUtil, never()).extractUsername(anyString());
        verifyNoInteractions(userDetailsService, orderService);
    }

    @Test
    void rejectedJwtLogsOnlyTypeWithCorrelationAndStillReturns401() throws Exception {
        String token = "very-secret-jwt";
        doThrow(new io.jsonwebtoken.JwtException("Bearer very-secret-jwt super-secret-password private_username"))
                .when(jwtUtil).extractUsername(token);
        try (var logs = new TestApplicationLogs()) {
            var result = mockMvc.perform(get("/api/v1/me/orders")
                            .header("Authorization", "Bearer " + token).header("X-Request-Id", "spoofed")
                            .queryParam("token", "query-private"))
                    .andExpect(status().isUnauthorized()).andReturn();
            String id = result.getResponse().getHeader("X-Request-Id");
            assertEquals(id, java.util.UUID.fromString(id).toString());
            assertEquals(1, logs.events("security.jwt.rejected").size());
            var event = logs.events("security.jwt.rejected").get(0);
            assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
            assertEquals(java.util.Map.of("event", "security.jwt.rejected",
                    "exceptionType", io.jsonwebtoken.JwtException.class.getName()), TestApplicationLogs.fields(event));
            assertEquals(id, event.getMDCPropertyMap().get("requestId"));
            assertEquals(1, logs.events("http.request.completed").size());
            assertEquals(401, TestApplicationLogs.fields(logs.events("http.request.completed").get(0)).get("status"));
            assertNull(org.slf4j.MDC.get("requestId"));
            logs.assertSafe(token, "private_username", "query-private", "spoofed");
        }
        verify(jwtUtil, times(1)).extractUsername(token);
        verifyNoInteractions(userDetailsService, orderService);
    }

    @Test
    void validJwtAuthenticatesAndIsParsedOnce() throws Exception {
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(User.withUsername("alice").password("unused").roles("USER").build());
        when(orderService.findMyOrders(any())).thenAnswer(call -> org.springframework.data.domain.Page.empty(call.getArgument(0)));
        String token = token(SECRET, ISSUER, new Date(System.currentTimeMillis() + 3_600_000L));

        mockMvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}"));

        verify(jwtUtil, times(1)).extractUsername(token);
        verify(userDetailsService, times(1)).loadUserByUsername("alice");
        verify(orderService, times(1)).findMyOrders(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-secret", "expired", "wrong-issuer", "missing-expiration", "malformed"})
    void invalidJwtReturns401AndDoesNotCallControllerService(String scenario) throws Exception {
        Date future = new Date(System.currentTimeMillis() + 3_600_000L);
        String token = switch (scenario) {
            case "wrong-secret" -> token(newSecret(), ISSUER, future);
            case "expired" -> token(SECRET, ISSUER, new Date(System.currentTimeMillis() - 3_600_000L));
            case "wrong-issuer" -> token(SECRET, "other-system", future);
            case "missing-expiration" -> token(SECRET, ISSUER, null);
            default -> "malformed-token";
        };

        mockMvc.perform(get("/api/v1/me/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verify(jwtUtil, times(1)).extractUsername(token);
        verifyNoInteractions(userDetailsService, orderService);
    }
}
