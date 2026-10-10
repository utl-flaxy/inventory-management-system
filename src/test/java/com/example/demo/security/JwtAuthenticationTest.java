package com.example.demo.security;

import com.example.demo.controller.OrderController;
import com.example.demo.service.OrderService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtUtil.class})
class JwtAuthenticationTest {

    private static final String ISSUER = "inventory-management-system";
    private static final String SECRET = newSecret();

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private JwtUtil jwtUtil;

    @MockBean
    private OrderService orderService;

    @MockBean
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
        mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());

        verify(jwtUtil, never()).extractUsername(anyString());
        verifyNoInteractions(userDetailsService, orderService);
    }

    @Test
    void validJwtAuthenticatesAndIsParsedOnce() throws Exception {
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(User.withUsername("alice").password("unused").roles("USER").build());
        when(orderService.findAll()).thenReturn(List.of());
        String token = token(SECRET, ISSUER, new Date(System.currentTimeMillis() + 3_600_000L));

        mockMvc.perform(get("/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(jwtUtil, times(1)).extractUsername(token);
        verify(userDetailsService, times(1)).loadUserByUsername("alice");
        verify(orderService, times(1)).findAll();
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

        mockMvc.perform(get("/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verify(jwtUtil, times(1)).extractUsername(token);
        verifyNoInteractions(userDetailsService, orderService);
    }
}
