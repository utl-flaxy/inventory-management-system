package com.example.demo.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {
    private static final String UID = java.util.UUID.randomUUID().toString();

    private static final String SECRET_A =
            Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String SECRET_B =
            Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());

    @Test
    void validTokenReturnsUuidAndGeneration() {
        JwtUtil jwtUtil = new JwtUtil(
                SECRET_A,
                "inventory-management-system",
                3_600_000L);

        String token = jwtUtil.generateToken(UID, 0);

        assertEquals(new JwtUtil.VerifiedJwt(UID, 0), jwtUtil.verify(token));
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

        String token = issuer.generateToken(UID, 0);

        assertThrows(JwtException.class, () -> verifier.verify(token));
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

        String token = issuer.generateToken(UID, 0);

        assertThrows(JwtException.class, () -> verifier.verify(token));
    }

    @ParameterizedTest
    @EnumSource(value = JwtNegativeTokens.Case.class, names = {"UNSIGNED", "PAYLOAD_TAMPERED", "WRONG_SIGNATURE"})
    void unsignedOrCryptographicallyModifiedTokenIsRejected(JwtNegativeTokens.Case scenario) {
        JwtUtil verifier = new JwtUtil(SECRET_A, "inventory-management-system", 3_600_000L);
        String token = JwtNegativeTokens.invalid(scenario, SECRET_A, SECRET_B, UID, java.util.UUID.randomUUID().toString());
        Class<? extends JwtException> expected = scenario == JwtNegativeTokens.Case.UNSIGNED
                ? UnsupportedJwtException.class : SignatureException.class;
        assertThrows(expected, () -> verifier.verify(token));
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
                .subject(UID).claim("uid", UID).claim("tokenVersion", "0")
                .issuer("inventory-management-system")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(ExpiredJwtException.class, () -> verifier.verify(token));
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        JwtUtil verifier = new JwtUtil(SECRET_A, "inventory-management-system", 3_600_000L);
        String token = Jwts.builder()
                .subject(UID).claim("uid", UID).claim("tokenVersion", "0")
                .issuer("inventory-management-system")
                .signWith(Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> verifier.verify(token));
    }

    @Test
    void secretLengthBoundaryIsEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtUtil("x".repeat(31), "inventory-management-system", 3_600_000L));
        JwtUtil verifier = new JwtUtil("x".repeat(32), "inventory-management-system", 3_600_000L);

        assertEquals(new JwtUtil.VerifiedJwt(UID, 0), verifier.verify(verifier.generateToken(UID, 0)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void blankIssuerIsRejected(String issuer) {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtUtil(SECRET_A, issuer, 3_600_000L));
    }
}
