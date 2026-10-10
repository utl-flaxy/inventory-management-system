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
