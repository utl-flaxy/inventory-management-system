package com.example.demo;

import org.junit.jupiter.params.provider.Arguments;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class TestAuthRequests {
    public static final String PASSWORD = "safe-auth-password-31";
    private TestAuthRequests() { }

    private static final java.util.concurrent.atomic.AtomicInteger CLIENTS = new java.util.concurrent.atomic.AtomicInteger();

    /** Distinct test callers without changing real admission limits or trusting request headers. */
    public static String nextClientAddress() {
        return "2001:db8::" + Integer.toHexString(CLIENTS.incrementAndGet());
    }

    public static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    public static Stream<Arguments> invalidApiRequests() {
        return Stream.of("/auth/register", "/auth/login").flatMap(endpoint -> Stream.concat(
                Stream.of(null, "", "   ", "a", "ab", "a".repeat(51), "a b", "日本語",
                                "control\u0001", "slash/name", "name@mail", " alice", "alice ", " alice ")
                        .map(value -> Arguments.of(endpoint, "username", credentials(value, PASSWORD))),
                Stream.of(null, "", "        ", "1234567", "a".repeat(73), "あ".repeat(25), "あ".repeat(24) + "a")
                        .map(value -> Arguments.of(endpoint, "password", credentials("alice", value)))));
    }
}
