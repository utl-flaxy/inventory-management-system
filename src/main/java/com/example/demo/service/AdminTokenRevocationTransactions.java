package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.exception.UserNotFoundException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AuthIdentity;
import com.example.demo.security.AuthPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTokenRevocationTransactions {
    private final UserRepository users;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record Proof(Long id, String uid, long version, String hash) {
        @Override public String toString() { return "AdminRevocationProof[redacted]"; }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(AuthPrincipal actor, Proof proof, String targetUid, String requestId) {
        AuthIdentity.requireUid(targetUid);
        AuthIdentity.requireUid(requestId);
        // Nonlocking resolution supplies only the immutable internal id for the global lock order.
        Long targetId = users.findByAuthUid(targetUid).map(User::getId).orElse(null);
        var locked = new HashMap<Long, User>();
        Stream.of(actor.id(), targetId).filter(java.util.Objects::nonNull).distinct().sorted().forEach(id -> {
            var found = users.findByIdForUpdate(id);
            if (found.isEmpty()) {
                if (id.equals(actor.id())) throw new AuthenticationCredentialsNotFoundException("認証が必要です");
                return; // Defer target absence until the fresh actor authorization check below.
            }
            var user = found.orElseThrow();
            entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            locked.put(id, user);
        });
        var administrator = locked.get(actor.id());
        if (!administrator.getId().equals(proof.id()) || !administrator.getAuthUid().equals(actor.uid())
                || !administrator.getAuthUid().equals(proof.uid()) || administrator.getTokenVersion() != actor.tokenVersion()
                || administrator.getTokenVersion() != proof.version() || !administrator.getPassword().equals(proof.hash()))
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        if (!"ROLE_ADMIN".equals(administrator.getRole())) throw new AccessDeniedException("権限がありません");
        var target = locked.get(targetId);
        if (target == null || !targetUid.equals(target.getAuthUid())) throw new UserNotFoundException();
        long generation = target.getTokenVersion();
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("Authentication generation exhausted");
        // Independent valid administrator requests each increment the freshly locked target generation.
        if (users.incrementTokenVersion(targetUid, generation) != 1)
            throw new AuthenticationCredentialsNotFoundException("認証が必要です");
        if (jdbc.update("INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) "
                + "VALUES (?,?,'FORCE_REVOKE','SUCCEEDED',?,?)", actor.id(), targetId, Timestamp.from(clock.instant()), requestId) != 1)
            throw new IllegalStateException("Authentication audit did not complete");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.atInfo().addKeyValue("event", "auth.force_revoke.succeeded")
                        .addKeyValue("actorUserId", actor.id()).addKeyValue("targetUserId", targetId)
                        .log("Administrator revoked authentication generation");
            }
        });
    }
}
