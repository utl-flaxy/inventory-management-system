package com.example.demo.controller;

import com.example.demo.security.AuthMutationRateLimiter;
import com.example.demo.security.AuthStateLocks;
import com.example.demo.security.CurrentUser;
import com.example.demo.security.LoginClientAddressFilter;
import com.example.demo.service.AuthRevocationTransactions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthRevocationController {
    private final AuthMutationRateLimiter limiter;
    private final AuthRevocationTransactions transactions;
    private final CurrentUser currentUser;

    @PostMapping("/auth/logout-all")
    @Operation(summary = "本人の全端末JWTを失効。再送した旧JWTは401となる")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "全端末失効をcommit済み"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED"),
            @ApiResponse(responseCode = "429", description = "AUTH_MUTATION_RATE_LIMITED; Retry-After seconds"),
            @ApiResponse(responseCode = "503", description = "AUTH_MUTATION_SOURCE_UNAVAILABLE / SERVICE_UNAVAILABLE"),
            @ApiResponse(responseCode = "500", description = "SYSTEM_ERROR")})
    public ResponseEntity<Void> logoutAll(HttpServletRequest request) {
        var principal = currentUser.requirePrincipal();
        try (var permit = limiter.acquire(LoginClientAddressFilter.source(request))) {
            try { transactions.logoutAll(principal, MDC.get("requestId")); }
            catch (RuntimeException failure) { throw AuthStateLocks.translate(failure); }
            return ResponseEntity.noContent().build();
        }
    }
}
