package com.example.demo.controller;

import com.example.demo.dto.AdminTokenRevocationRequest;
import com.example.demo.security.AuthMutationRateLimiter;
import com.example.demo.security.AuthStateLocks;
import com.example.demo.security.CurrentUser;
import com.example.demo.security.LoginClientAddressFilter;
import com.example.demo.service.AdminTokenRevocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTokenRevocationController {
    private final AuthMutationRateLimiter limiter;
    private final CurrentUser currentUser;
    private final AdminTokenRevocationService service;

    @PostMapping("/api/v1/admin/users/{uid}/token-revocations")
    @Operation(summary = "ADMIN本人を再認証し、指定UIDの全端末JWTを失効。自己失効も可、新JWTは返さない")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "対象世代とFORCE_REVOKE監査をcommit済み"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / INVALID_REQUEST"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS / UNAUTHENTICATED"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"),
            @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND; ADMINのみ"),
            @ApiResponse(responseCode = "429", description = "AUTH_MUTATION_RATE_LIMITED; Retry-After seconds"),
            @ApiResponse(responseCode = "503", description = "AUTH_MUTATION_SOURCE_UNAVAILABLE / SERVICE_UNAVAILABLE"),
            @ApiResponse(responseCode = "500", description = "SYSTEM_ERROR")})
    public ResponseEntity<Void> revoke(
            @Parameter(schema = @Schema(type = "string", format = "uuid", description = "canonical lowercase UUID v4"))
            @PathVariable String uid, @Valid @RequestBody AdminTokenRevocationRequest body, HttpServletRequest request) {
        var actor = currentUser.requirePrincipal();
        try (var permit = limiter.acquire(AuthMutationRateLimiter.Operation.FORCE_REVOKE, LoginClientAddressFilter.source(request))) {
            try { service.revoke(actor, uid, body, MDC.get("requestId")); }
            catch (RuntimeException failure) { throw AuthStateLocks.translate(failure); }
            return ResponseEntity.noContent().build();
        }
    }
}
