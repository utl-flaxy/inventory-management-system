package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
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
    @Column(nullable = false)
    private String password;

    // 権限
    // ROLE_USER
    // ROLE_ADMIN
    @Column(nullable = false)
    private String role;

}