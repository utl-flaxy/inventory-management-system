package com.example.demo.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

/** Runtime-only attack vectors: no reusable key or compact JWT is stored in source. */
final class JwtNegativeTokens {
    enum Case { UNSIGNED, PAYLOAD_TAMPERED, WRONG_SIGNATURE, WRONG_KEY, WRONG_ISSUER, EXPIRED }

    private JwtNegativeTokens() { }

    static String invalid(Case scenario, String secret, String otherSecret, String user, String admin) {
        long now = System.currentTimeMillis();
        String[] original = signed(secret, user, "inventory-management-system", now + 3_600_000).split("\\.", -1);
        String[] privileged = signed(secret, admin, "inventory-management-system", now + 3_600_000).split("\\.", -1);
        return switch (scenario) {
            case UNSIGNED -> encode("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + privileged[1] + ".";
            // Keep the original header and MAC; change sub to an existing ADMIN without re-signing.
            case PAYLOAD_TAMPERED -> original[0] + "." + privileged[1] + "." + original[2];
            case WRONG_SIGNATURE -> {
                byte[] signature = Base64.getUrlDecoder().decode(original[2]);
                signature[0] ^= 1; // Mutate significant MAC bits, not unused base64 padding bits.
                yield original[0] + "." + original[1] + "." + encode(signature);
            }
            case WRONG_KEY -> signed(otherSecret, admin, "inventory-management-system", now + 3_600_000);
            case WRONG_ISSUER -> signed(secret, admin, "other-system", now + 3_600_000);
            case EXPIRED -> signed(secret, admin, "inventory-management-system", now - 3_600_000);
        };
    }

    private static String signed(String secret, String subject, String issuer, long expiration) {
        return Jwts.builder().subject(subject).issuer(issuer).issuedAt(new Date(expiration - 3_600_000))
                .expiration(new Date(expiration))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
