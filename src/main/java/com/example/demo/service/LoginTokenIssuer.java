package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginTokenIssuer {
    private final UserRepository users;
    private final EntityManager entities;
    private final JwtUtil jwt;

    // A detached immutable copy prevents refresh from modifying the original comparison state.
    public record Snapshot(Long id, String uid, String username, String hash, long version) {
        public static Snapshot of(User user) {
            return new Snapshot(user.getId(), user.getAuthUid(), user.getUsername(), user.getPassword(), user.getTokenVersion());
        }
        @Override public String toString() { return "LoginSnapshot[redacted]"; }
    }
    public record Issued(String token, Long userId, String role) {
        @Override public String toString() { return "IssuedToken[redacted]"; }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Issued issue(Snapshot snapshot) {
        User current = users.findByIdForUpdate(snapshot.id()).orElseThrow(InvalidCredentialsException::new);
        entities.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        if (!snapshot.uid().equals(current.getAuthUid()) || !snapshot.username().equals(current.getUsername())
                || !snapshot.hash().equals(current.getPassword()) || snapshot.version() != current.getTokenVersion())
            throw new InvalidCredentialsException();
        return new Issued(jwt.generateToken(current.getAuthUid(), current.getTokenVersion()), current.getId(), current.getRole());
    }
}
