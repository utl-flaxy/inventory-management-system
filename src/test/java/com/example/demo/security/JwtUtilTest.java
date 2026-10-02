package com.example.demo.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static final String SECRET_A =
            "0123456789abcdef0123456789abcdef";
    private static final String SECRET_B =
            "abcdef0123456789abcdef0123456789";

    @Test
    void validTokenReturnsUsername() {
        JwtUtil jwtUtil = new JwtUtil(
                SECRET_A,
                "inventory-management-system",
                3_600_000L);

        String token = jwtUtil.generateToken("alice");

        assertEquals("alice", jwtUtil.extractUsername(token));
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtUtil issuer = new JwtUtil(
                SECRET_A,
                "inventory-management-system",
                3_600_000L);
        JwtUtil verifier = new JwtUtil(
                SECRET_B,
                "inventory-management-system",
                3_600_000L);

        String token = issuer.generateToken("alice");

        assertThrows(JwtException.class, () -> verifier.extractUsername(token));
    }

    @Test
    void tokenWithDifferentIssuerIsRejected() {
        JwtUtil issuer = new JwtUtil(
                SECRET_A,
                "other-system",
                3_600_000L);
        JwtUtil verifier = new JwtUtil(
                SECRET_A,
                "inventory-management-system",
                3_600_000L);

        String token = issuer.generateToken("alice");

        assertThrows(JwtException.class, () -> verifier.extractUsername(token));
    }

    @Test
    void secretShorterThan32BytesIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtUtil("too-short", "inventory-management-system", 3_600_000L));
    }

    @Test
    void nonPositiveExpirationIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtUtil(SECRET_A, "inventory-management-system", 0L));
    }
}
