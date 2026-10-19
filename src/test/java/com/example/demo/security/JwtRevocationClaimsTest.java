package com.example.demo.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JwtRevocationClaimsTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    private static final String UID = java.util.UUID.randomUUID().toString();
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private JwtUtil jwt(Instant time) { return new JwtUtil(SECRET, "inventory-management-system", 1000, Clock.fixed(time, ZoneOffset.UTC)); }

    @Test void issuedClaimsUseUuidAndCanonicalGenerationWithoutUsernameOrRole() {
        String token = jwt(NOW).generateToken(UID, 7);
        assertEquals(new JwtUtil.VerifiedJwt(UID, 7), jwt(NOW).verify(token));
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW)).build().parseSignedClaims(token).getPayload();
        assertEquals(java.util.Set.of("sub", "uid", "tokenVersion", "iss", "iat", "exp"), claims.keySet());
        assertEquals(UID, claims.getSubject());assertEquals(UID, claims.get("uid"));assertEquals("7", claims.get("tokenVersion"));
        assertEquals(Date.from(NOW), claims.getIssuedAt());assertEquals(Date.from(NOW.plusSeconds(1)), claims.getExpiration());
    }

    @Test void expirationBoundaryUsesTheInjectedClockWithoutSleep() {
        String token = jwt(NOW).generateToken(UID, 0);
        assertDoesNotThrow(() -> jwt(NOW.plusMillis(999)).verify(token));
        assertThrows(JwtException.class, () -> jwt(NOW.plusSeconds(1)).verify(token));
        assertThrows(JwtException.class, () -> jwt(NOW.plusSeconds(2)).verify(token));
    }

    static Stream<Object> invalidVersions() {
        return Stream.of("", "-1", "00", "01", " 0", "0 ", "+1", "1.0", "9223372036854775808", 0, 1L, true, java.util.Map.of("value", "0"));
    }
    @ParameterizedTest @MethodSource("invalidVersions")
    void signedButInvalidGenerationIsRejected(Object version) {
        assertThrows(RuntimeException.class, () -> jwt(NOW).verify(signed(UID, UID, version, true)));
    }

    @Test void missingClaimsLegacySubjectAndDifferentSubjectsAreRejected() {
        assertThrows(JwtException.class, () -> jwt(NOW).verify(signed("alice", null, null, true)));
        assertThrows(JwtException.class, () -> jwt(NOW).verify(signed(UID, UID, null, true)));
        assertThrows(JwtException.class, () -> jwt(NOW).verify(signed(UID, java.util.UUID.randomUUID().toString(), "0", true)));
        assertThrows(JwtException.class, () -> jwt(NOW).verify(signed(UID, UID, "0", false)));
    }

    @Test void invalidUuidAndVersionOverflowCannotBeIssued() {
        assertThrows(IllegalArgumentException.class, () -> jwt(NOW).generateToken("alice", 0));
        assertThrows(IllegalArgumentException.class, () -> jwt(NOW).generateToken("A"+UID.substring(1), 0));
        assertThrows(IllegalStateException.class, () -> jwt(NOW).generateToken(UID, -1));
        assertThrows(IllegalStateException.class, () -> jwt(NOW).generateToken(UID, Long.MAX_VALUE));
    }

    private String signed(String subject, Object uid, Object version, boolean iat) {
        var builder = Jwts.builder().subject(subject).claim("uid", uid).claim("tokenVersion", version)
                .issuer("inventory-management-system").expiration(Date.from(NOW.plusSeconds(1)));
        if (iat) builder.issuedAt(Date.from(NOW));
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
