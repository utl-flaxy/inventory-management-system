package com.example.demo.service;

import com.example.demo.repository.UserRepository;
import com.example.demo.security.AuthIdentity;
import com.example.demo.security.AuthPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthRevocationTransactions {
    private final UserRepository users;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void logoutAll(AuthPrincipal actor, String requestId) {
        AuthIdentity.requireUid(requestId); // Only the server-generated correlation ID is admitted.
        var user = users.findByAuthUidForUpdate(actor.uid())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証が必要です"));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.getId().equals(actor.id()) || user.getTokenVersion() != actor.tokenVersion())
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        if (user.getTokenVersion() == Long.MAX_VALUE)
            throw new IllegalStateException("Authentication generation exhausted");
        Long userId = user.getId();
        if (users.incrementTokenVersion(actor.uid(), actor.tokenVersion()) != 1)
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        if (jdbc.update("INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) "
                + "VALUES (?,?,'LOGOUT_ALL','SUCCEEDED',?,?)", userId, userId, Timestamp.from(clock.instant()), requestId) != 1)
            throw new IllegalStateException("Authentication audit did not complete");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.atInfo().addKeyValue("event", "auth.logout_all.succeeded")
                        .addKeyValue("actorUserId", userId).addKeyValue("targetUserId", userId)
                        .log("Authentication generation revoked");
            }
        });
    }
}
