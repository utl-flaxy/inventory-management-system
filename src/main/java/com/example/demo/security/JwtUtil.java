package com.example.demo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final String issuer;
    private final long expirationMs;
    private final Clock clock;

    @Autowired
    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer:inventory-management-system}") String issuer,
            @Value("${app.jwt.expiration-ms:3600000}") long expirationMs, Clock clock) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.jwt.secret must not be blank");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "app.jwt.secret must be at least 32 bytes for HS256");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("app.jwt.issuer must not be blank");
        }
        if (expirationMs <= 0) {
            throw new IllegalArgumentException("app.jwt.expiration-ms must be positive");
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = issuer;
        this.expirationMs = expirationMs;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public JwtUtil(String secret, String issuer, long expirationMs) {
        this(secret, issuer, expirationMs, Clock.systemUTC());
    }

    public record VerifiedJwt(String uid, long tokenVersion) { }

    public String generateToken(String uid, long tokenVersion) {
        AuthIdentity.requireUid(uid);
        if (tokenVersion < 0 || tokenVersion == Long.MAX_VALUE)
            throw new IllegalStateException("Authentication generation exhausted");

        Date now = Date.from(clock.instant());

        return Jwts.builder()
                .subject(uid)
                .claim("uid", uid)
                .claim("tokenVersion", Long.toString(tokenVersion))
                .issuer(issuer)
                .issuedAt(now)
                .expiration(new Date(Math.addExact(now.getTime(), expirationMs)))
                .signWith(key)
                .compact();
    }

    public VerifiedJwt verify(String token) {
        Claims claims = parseClaims(token);
        Object rawUid = claims.get("uid");
        Object rawVersion = claims.get("tokenVersion");
        if (!(rawUid instanceof String uid) || !(rawVersion instanceof String version)
                || !uid.equals(claims.getSubject()) || claims.getIssuedAt() == null)
            throw new JwtException("Required authentication claims are invalid");
        return new VerifiedJwt(AuthIdentity.requireUid(uid), AuthIdentity.requireVersion(version));
    }

    private Claims parseClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (claims.getExpiration() == null) {
            throw new JwtException("JWT expiration must be specified");
        }
        if (!clock.instant().isBefore(claims.getExpiration().toInstant()))
            throw new JwtException("JWT has expired");
        return claims;
    }
}
