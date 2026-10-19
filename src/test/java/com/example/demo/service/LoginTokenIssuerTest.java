package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginTokenIssuerTest {
    @Mock UserRepository users;
    @Mock EntityManager entities;
    @Mock JwtUtil jwt;
    @InjectMocks LoginTokenIssuer issuer;

    @Test void freshLockedStateIsCheckedBeforeExactlyOneSigningCall() {
        var user = new User(1L, "alice", "private-hash", "ROLE_USER");
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(jwt.generateToken(user.getAuthUid(), 0)).thenReturn("opaque-test-result");
        var result = issuer.issue(LoginTokenIssuer.Snapshot.of(user));
        assertEquals("opaque-test-result", result.token());
        var ordered = inOrder(users, entities, jwt);
        ordered.verify(users).findByIdForUpdate(1L);
        ordered.verify(entities).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        ordered.verify(jwt).generateToken(user.getAuthUid(), 0);
        verifyNoMoreInteractions(users, entities, jwt);
        assertFalse(result.toString().contains("opaque-test-result"));
    }

    @ParameterizedTest @ValueSource(strings={"hash","uid","username","version"})
    void changedStateNeverUpgradesAnOldPasswordVerificationToANewGeneration(String change) {
        var user = new User(1L, "alice", "private-hash", "ROLE_USER");
        var snapshot = LoginTokenIssuer.Snapshot.of(user);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        doAnswer(call -> {
            switch (change) {
                case "hash" -> user.setPassword("different-private-hash");
                case "uid" -> ReflectionTestUtils.setField(user,"authUid",java.util.UUID.randomUUID().toString());
                case "username" -> user.setUsername("different-user");
                case "version" -> ReflectionTestUtils.setField(user,"tokenVersion",1L);
                default -> throw new AssertionError();
            }
            return null;
        }).when(entities).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(InvalidCredentialsException.class, () -> issuer.issue(snapshot));
        verifyNoInteractions(jwt);
        assertFalse(snapshot.toString().contains("private-hash"));
        assertFalse(snapshot.toString().contains("alice"));
    }

    @Test void disappearedUserCannotReceiveAToken() {
        var snapshot = LoginTokenIssuer.Snapshot.of(new User(1L,"alice","private-hash","ROLE_USER"));
        assertThrows(InvalidCredentialsException.class, () -> issuer.issue(snapshot));
        verifyNoInteractions(entities,jwt);
    }
}
