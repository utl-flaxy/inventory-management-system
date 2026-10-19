package com.example.demo.security;

import java.util.UUID;

/** Authentication identifiers are never usernames or business primary keys. */
public final class AuthIdentity {
    private AuthIdentity() { }

    public static String requireUid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid authentication identifier");
        UUID.fromString(value);
        return value;
    }

    public static long requireVersion(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Invalid authentication generation");
        return Long.parseLong(value);
    }
}
