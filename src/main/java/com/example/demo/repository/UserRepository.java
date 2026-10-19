package com.example.demo.repository;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(Long id);

    // ユーザー名で検索
    Optional<User> findByUsername(String username);

    Optional<User> findByAuthUid(String authUid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.authUid = :uid")
    Optional<User> findByAuthUidForUpdate(String uid);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.authUid = :uid "
            + "and u.tokenVersion = :expected and u.tokenVersion < 9223372036854775807")
    int incrementTokenVersion(String uid, long expected);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.password = :newHash, u.tokenVersion = u.tokenVersion + 1 where u.authUid = :uid "
            + "and u.tokenVersion = :expected and u.password = :previousHash and u.tokenVersion < 9223372036854775807")
    int changePasswordAndIncrementVersion(String uid, long expected, String previousHash, String newHash);

}
