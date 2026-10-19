package com.example.demo.service;

import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.RegisterRequest;
import com.example.demo.entity.User;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.exception.UsernameAlreadyExistsException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.LoginDummyPassword;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.util.Optional;

import static com.example.demo.TestAuthRequests.PASSWORD;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private LoginTokenIssuer jwt;
    @Mock private LoginDummyPassword dummy;
    @InjectMocks private AuthService service;

    @Test
    void registerEncodesOriginalPasswordForcesUserAndFlushesExactlyOnce() {
        when(encoder.encode(PASSWORD)).thenReturn("stored-hash");
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.register(registration());
        assertEquals("alice", result.getUsername());
        assertEquals("stored-hash", result.getPassword());
        assertEquals("ROLE_USER", result.getRole());
        verify(users).findByUsername("alice");
        verify(users).saveAndFlush(result);
        verifyNoMoreInteractions(users);
        verify(encoder).encode(PASSWORD);
        verifyNoInteractions(jwt);
    }

    @Test
    void existingUserIsConflictWithoutEncodingOrSaving() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(user()));
        assertThrows(UsernameAlreadyExistsException.class, () -> service.register(registration()));
        verify(users).findByUsername("alice");
        verifyNoMoreInteractions(users);
        verifyNoInteractions(encoder, jwt);
    }

    @Test
    void namedUsernameUniqueFailureIsConflictWithoutQueryingAgainAfterRollback() {
        var failure = violation(1062, "uk_users_username");
        stubSaveFailure(failure);
        var exception = assertThrows(UsernameAlreadyExistsException.class, () -> service.register(registration()));
        assertSame(failure, exception.getCause());
        verify(users).findByUsername("alice");
        verify(users).saveAndFlush(any());
        verifyNoMoreInteractions(users);
        verifyNoInteractions(jwt);
    }

    @ParameterizedTest
    @CsvSource({"1062,uk_orders_customer_idempotency_key", "1062,another_unique",
            "1048,uk_users_username", "1062,uk_users_username_other"})
    void unrelatedConstraintsAreNotMisclassifiedAsUsernameDuplicates(int code, String name) {
        var failure = violation(code, name);
        stubSaveFailure(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.register(registration())));
        verify(users).findByUsername("alice");
        verify(users).saveAndFlush(any());
        verifyNoMoreInteractions(users);
    }

    @Test
    void genericDatabaseFailureIsNotConvertedToDuplicate() {
        var failure = new DataIntegrityViolationException("unrelated");
        stubSaveFailure(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.register(registration())));
    }

    @Test
    void nonexistentUserAndWrongPasswordHaveTheSameExceptionAndNoJwt() {
        when(dummy.encoded()).thenReturn("dummy-hash");
        when(users.findByUsername("alice")).thenReturn(Optional.empty(), Optional.of(user()));
        var absent = assertThrows(InvalidCredentialsException.class, () -> service.login(login()));
        var mismatch = assertThrows(InvalidCredentialsException.class, () -> service.login(login()));
        assertEquals(absent.getMessage(), mismatch.getMessage());
        verify(users, times(2)).findByUsername("alice");
        verifyNoMoreInteractions(users);
        verify(encoder).matches(PASSWORD, "stored-hash");
        verify(encoder).matches(PASSWORD, "dummy-hash");
        verifyNoMoreInteractions(encoder);
        verify(encoder, never()).encode(any());
        verifyNoInteractions(jwt);
    }

    @Test
    void evenAMatchingDummyPasswordCannotAuthenticateAnUnknownUser() {
        when(dummy.encoded()).thenReturn("dummy-hash");
        when(encoder.matches(PASSWORD, "dummy-hash")).thenReturn(true);
        assertThrows(InvalidCredentialsException.class, () -> service.login(login()));
        verify(encoder).matches(PASSWORD, "dummy-hash");
        verifyNoInteractions(jwt);
    }

    @Test
    void successfulLoginQueriesOnceMatchesPasswordAndPassesImmutableSnapshotToLockedIssuer() {
        User saved = user();
        when(users.findByUsername("alice")).thenReturn(Optional.of(saved));
        when(encoder.matches(PASSWORD, "stored-hash")).thenReturn(true);
        when(jwt.issue(any())).thenReturn(new LoginTokenIssuer.Issued("test-token", 1L, "ROLE_USER"));
        assertEquals("test-token", service.login(login()).getToken());
        verify(users).findByUsername("alice");
        verifyNoMoreInteractions(users);
        verify(encoder).matches(PASSWORD, "stored-hash");
        verify(jwt).issue(LoginTokenIssuer.Snapshot.of(saved));
        verifyNoMoreInteractions(jwt);
    }

    private User user() { return new User(1L, "Alice", "stored-hash", "ROLE_USER"); }
    private RegisterRequest registration() {
        var request = new RegisterRequest(); request.setUsername("alice"); request.setPassword(PASSWORD); return request;
    }
    private LoginRequest login() {
        var request = new LoginRequest(); request.setUsername("alice"); request.setPassword(PASSWORD); return request;
    }
    private DataIntegrityViolationException violation(int code, String name) {
        return new DataIntegrityViolationException("constraint", new ConstraintViolationException("constraint",
                new SQLException("Duplicate entry 'uk_users_username' for key unrelated", "23000", code), "insert", name));
    }
    private void stubSaveFailure(DataIntegrityViolationException failure) {
        when(encoder.encode(PASSWORD)).thenReturn("stored-hash");
        when(users.saveAndFlush(any())).thenThrow(failure);
    }
}
