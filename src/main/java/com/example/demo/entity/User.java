package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "users")
@Check(name = "ck_users_role_allowed", constraints = "role IN ('ROLE_USER', 'ROLE_ADMIN')")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Builder.Default
    @Column(name = "auth_uid", nullable = false, updatable = false, unique = true, length = 36,
            columnDefinition = "char(36)")
    private String authUid = java.util.UUID.randomUUID().toString();

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Builder.Default
    @Column(name = "token_version", nullable = false, columnDefinition = "bigint default 0")
    private long tokenVersion = 0;

    public User(Long id, String username, String password, String role) {
        this();
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
    }

    @PrePersist
    void validateAuthenticationIdentity() {
        com.example.demo.security.AuthIdentity.requireUid(authUid);
        if (tokenVersion < 0) throw new IllegalArgumentException("Invalid authentication generation");
    }

    // ユーザー名
    @Column(nullable = false, unique = true)
    private String username;

    // パスワード（BCryptで暗号化）
    // 登録APIがUserを返しても、認証用ハッシュはJSONへ出力しない。
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    // 権限
    // ROLE_USER
    // ROLE_ADMIN
    @Column(nullable = false)
    private String role;

}
