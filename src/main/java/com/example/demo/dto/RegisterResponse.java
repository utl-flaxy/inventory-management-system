package com.example.demo.dto;

import com.example.demo.entity.User;

public record RegisterResponse(Long id, String username, String role) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
