package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUser {
    private final UserRepository userRepository;

    public User requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        }
        if (!(authentication.getPrincipal() instanceof AuthPrincipal principal))
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        return userRepository.findByAuthUid(principal.uid())
                .filter(user -> user.getId().equals(principal.id()) && user.getTokenVersion() == principal.tokenVersion())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証ユーザーが存在しません"));
    }

    public AuthPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal))
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        return principal;
    }
}
