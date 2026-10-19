package com.example.demo.service;

import com.example.demo.dto.PasswordChangeRequest;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.exception.InvalidNewPasswordException;
import com.example.demo.provisioning.InitialAdminProvisioner;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/** Called inside the bounded mutation permit, but outside a DB transaction/row lock. */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final PasswordChangeTransactions transactions;

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public void change(AuthPrincipal actor, PasswordChangeRequest request, String requestId) {
        var user = users.findByAuthUid(actor.uid())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証が必要です"));
        if (!user.getId().equals(actor.id()) || user.getTokenVersion() != actor.tokenVersion())
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        var snapshot = new PasswordChangeTransactions.Snapshot(user.getId(), user.getAuthUid(),
                user.getTokenVersion(), user.getPassword(), user.getRole());
        if ("ROLE_ADMIN".equals(snapshot.role())) {
            char[] temporary = request.getNewPassword().toCharArray();
            try { InitialAdminProvisioner.validatePassword(temporary); }
            catch (IllegalArgumentException invalid) { throw new InvalidNewPasswordException(); }
            finally { Arrays.fill(temporary, '\0'); }
        }
        if (!passwords.matches(request.getCurrentPassword(), snapshot.hash())) throw new InvalidCredentialsException();
        String newHash = passwords.encode(request.getNewPassword());
        transactions.change(actor, snapshot, newHash, requestId);
    }
}
