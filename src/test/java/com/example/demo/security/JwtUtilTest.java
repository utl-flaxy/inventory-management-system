package com.example.demo.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static final String SECRET_A =
            Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String SECRET_B =
            Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());

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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "too-short", "                                "})
    void missingBlankOrWeakSecretIsRejected(String secret) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtUtil(secret, "inventory-management-system", 3_600_000L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveExpirationIsRejected(long expirationMs) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtUtil(SECRET_A, "inventory-management-system", expirationMs));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtUtil verifier = new JwtUtil(SECRET_A, "inventory-management-system", 3_600_000L);
        String token = Jwts.builder()
                .subject("alice")
                .issuer("inventory-management-system")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(ExpiredJwtException.class, () -> verifier.extractUsername(token));
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        JwtUtil verifier = new JwtUtil(SECRET_A, "inventory-management-system", 3_600_000L);
        String token = Jwts.builder()
                .subject("alice")
                .issuer("inventory-management-system")
                .signWith(Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> verifier.extractUsername(token));
    }

    @Test
    void secretLengthBoundaryIsEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtUtil("x".repeat(31), "inventory-management-system", 3_600_000L));
        JwtUtil verifier = new JwtUtil("x".repeat(32), "inventory-management-system", 3_600_000L);

        assertEquals("alice", verifier.extractUsername(verifier.generateToken("alice")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void blankIssuerIsRejected(String issuer) {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtUtil(SECRET_A, issuer, 3_600_000L));
    }
}
