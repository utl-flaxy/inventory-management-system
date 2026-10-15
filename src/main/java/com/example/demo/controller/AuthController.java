package com.example.demo.controller;

import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.dto.RegisterRequest;
import com.example.demo.dto.RegisterResponse;
import com.example.demo.exception.InvalidCredentialsException;
import com.example.demo.exception.LoginRateLimitExceededException;
import com.example.demo.security.LoginClientAddressFilter;
import com.example.demo.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
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
    private final LoginRateLimiter loginRateLimiter;

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
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS"),
            @ApiResponse(responseCode = "429", description = "LOGIN_RATE_LIMITED; Retry-After seconds")})
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {

        try (var permit = loginRateLimiter.acquire(LoginClientAddressFilter.source(servletRequest), request.getUsername())) {
            return authService.login(request);
        } catch (LoginRateLimitExceededException ex) {
            log.atWarn().addKeyValue("event", "auth.login.throttled").log("Login rate limited");
            throw ex;
        } catch (InvalidCredentialsException ex) {
            log.atWarn().addKeyValue("event", "auth.login.failed").log("Login failed");
            throw ex;
        }
    }
}
