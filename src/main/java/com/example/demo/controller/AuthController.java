package com.example.demo.controller;

import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.dto.RegisterRequest;
import com.example.demo.dto.RegisterResponse;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.service.AuthService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    // ユーザー登録
    @PostMapping("/register")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "ROLE_USERで登録"),
            @ApiResponse(responseCode = "400", description = "入力形式が不正"),
            @ApiResponse(responseCode = "409", description = "USERNAME_ALREADY_EXISTS")})
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {

        RegisterResponse response = RegisterResponse.from(authService.register(request));
        log.atInfo().addKeyValue("event", "auth.register.succeeded")
                .addKeyValue("userId", response.id()).addKeyValue("role", response.role())
                .log("Registration succeeded");
        return response;
    }

    // ログイン
    @PostMapping("/login")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "JWTを返却"),
            @ApiResponse(responseCode = "400", description = "入力形式が不正"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")})
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {

        try {
            return authService.login(request);
        } catch (InvalidCredentialsException ex) {
            log.atWarn().addKeyValue("event", "auth.login.failed").log("Login failed");
            throw ex;
        }
    }
}
