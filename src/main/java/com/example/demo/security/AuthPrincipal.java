package com.example.demo.security;

import java.security.Principal;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Per-request verified identity. Contains no credential, username or bearer token. */
public record AuthPrincipal(Long id, String uid, long tokenVersion, String role) implements Principal {
    public AuthPrincipal {
        AuthIdentity.requireUid(uid);
        if (id == null || tokenVersion < 0 || !("ROLE_USER".equals(role) || "ROLE_ADMIN".equals(role)))
            throw new IllegalArgumentException("Invalid persisted authentication state");
    }

    @Override public String getName() { return uid; }
    public List<SimpleGrantedAuthority> authorities() { return List.of(new SimpleGrantedAuthority(role)); }
}
