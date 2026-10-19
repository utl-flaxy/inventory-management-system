package com.example.demo.service;

import com.example.demo.dto.AdminTokenRevocationRequest;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AuthIdentity;
import com.example.demo.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminTokenRevocationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AdminTokenRevocationTransactions transactions;

    /** The controller holds the shared mutation permit; BCrypt never holds a row lock/transaction. */
    @Transactional(propagation = Propagation.NEVER)
    public void revoke(AuthPrincipal actor, String targetUid, AdminTokenRevocationRequest request, String requestId) {
        var user = users.findByAuthUid(actor.uid())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証が必要です"));
        if (!user.getId().equals(actor.id()) || user.getTokenVersion() != actor.tokenVersion())
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        if (!"ROLE_ADMIN".equals(user.getRole())) throw new AccessDeniedException("権限がありません");
        try { AuthIdentity.requireUid(targetUid); }
        catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        var proof = new AdminTokenRevocationTransactions.Proof(user.getId(), user.getAuthUid(),
                user.getTokenVersion(), user.getPassword());
        if (!passwords.matches(request.getCurrentPassword(), proof.hash())) throw new InvalidCredentialsException();
        transactions.revoke(actor, proof, targetUid, requestId);
    }
}
