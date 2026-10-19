package com.example.demo;

import com.example.demo.security.AuthPrincipal;
import com.example.demo.security.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Fixtures resolve a persisted identity; production token issuance never looks up a username here. */
public final class TestAuth {
    private TestAuth() { }
    public static AuthPrincipal principal(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("SELECT id,auth_uid,token_version,role FROM users WHERE username = ?",
                (row, n) -> new AuthPrincipal(row.getLong("id"), row.getString("auth_uid"),
                        row.getLong("token_version"), row.getString("role")), username);
    }
    public static String token(JwtUtil jwt, JdbcTemplate jdbc, String username) {
        var user = principal(jdbc, username);
        return jwt.generateToken(user.uid(), user.tokenVersion());
    }
    public static void authenticate(JdbcTemplate jdbc, String username) {
        var user = principal(jdbc, username);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
    }
}
