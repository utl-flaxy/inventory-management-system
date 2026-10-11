package com.example.demo.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LoginDummyPassword {
    private final String encoded;

    public LoginDummyPassword(PasswordEncoder encoder) {
        // Same encoder/cost as registration; random unknown plaintext, computed once at startup.
        encoded = encoder.encode(UUID.randomUUID().toString());
    }

    public String encoded() { return encoded; }
}
