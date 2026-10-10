package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentUserTest {
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private CurrentUser currentUser;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedPrincipalResolvesExistingUser() {
        User user = com.example.demo.TestOrders.customer();
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("alice", "unused", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        assertSame(user, currentUser.requireUser());
        verify(userRepository).findByUsername("alice");
    }

    @Test
    void missingAuthenticationIsRejectedBeforeQueryingUsers() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void unauthenticatedCredentialsAreRejected() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused"));
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void anonymousAuthenticationIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::requireUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void deletedUserIsRejected() {
        com.example.demo.TestOrders.authenticate();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::requireUser);
    }
}
