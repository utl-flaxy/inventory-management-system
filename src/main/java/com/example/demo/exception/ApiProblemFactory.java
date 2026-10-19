package com.example.demo.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class ApiProblemFactory {
    public static final String TYPE_BASE = "https://inventory-management-system.example/problems/";
    public static final String UNAUTHENTICATED_DETAIL = "認証が必要です";
    public static final String ACCESS_DENIED_DETAIL = "この操作を実行する権限がありません";
    public static final String INVALID_REQUEST_DETAIL = "リクエストの形式が不正です";
    public static final String SYSTEM_ERROR_DETAIL = "予期しないエラーが発生しました";
    public static final String SERVICE_UNAVAILABLE_DETAIL = "一時的にサービスを利用できません。時間をおいて再試行してください";

    private ApiProblemFactory() { }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail,
                                       HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title(code));
        problem.setInstance(instance(request));
        problem.setProperty("code", code);
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(HttpStatusCode status, String code, String detail,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(create(status, code, detail, request));
    }

    public static String title(String code) {
        return switch (code) {
            case "USERNAME_ALREADY_EXISTS" -> "Username Already Exists";
            case "INVALID_CREDENTIALS" -> "Invalid Credentials";
            case "LOGIN_RATE_LIMITED" -> "Login Rate Limited";
            case "LOGIN_SOURCE_UNAVAILABLE" -> "Login Source Unavailable";
            case "REGISTRATION_RATE_LIMITED" -> "Registration Rate Limited";
            case "REGISTRATION_SOURCE_UNAVAILABLE" -> "Registration Source Unavailable";
            case "AUTH_MUTATION_RATE_LIMITED" -> "Authentication Mutation Rate Limited";
            case "AUTH_MUTATION_SOURCE_UNAVAILABLE" -> "Authentication Mutation Source Unavailable";
            case "UNSUPPORTED_MEDIA_TYPE" -> "Unsupported Media Type";
            case "INVALID_REQUEST" -> "Invalid Request";
            case "INVALID_IDEMPOTENCY_KEY" -> "Invalid Idempotency Key";
            case "IDEMPOTENCY_KEY_CONFLICT" -> "Idempotency Key Conflict";
            case "UNAUTHENTICATED" -> "Unauthenticated";
            case "ACCESS_DENIED" -> "Access Denied";
            case "CONCURRENT_UPDATE" -> "Concurrent Update";
            case "INVALID_PRODUCT_UPDATE" -> "Invalid Product Update";
            case "INVALID_INVENTORY_ADJUSTMENT" -> "Invalid Inventory Adjustment";
            case "INVENTORY_ADJUSTMENT_CONFLICT" -> "Inventory Adjustment Conflict";
            case "ILLEGAL_ORDER_STATE_TRANSITION" -> "Illegal Order State Transition";
            case "ORDER_INVENTORY_RESTORATION_FAILED" -> "Order Inventory Restoration Failed";
            case "ORDER_NOT_FOUND" -> "Order Not Found";
            case "PRODUCT_NOT_FOUND" -> "Product Not Found";
            case "PRODUCT_DELETE_CONFLICT" -> "Product Delete Conflict";
            case "INVALID_ORDER_REQUEST" -> "Invalid Order Request";
            case "OUT_OF_STOCK" -> "Out of Stock";
            case "VALIDATION_ERROR" -> "Validation Error";
            case "SYSTEM_ERROR" -> "System Error";
            case "SERVICE_UNAVAILABLE" -> "Service Unavailable";
            default -> throw new IllegalArgumentException("Unknown API problem code");
        };
    }

    private static URI instance(HttpServletRequest request) {
        String path = request.getRequestURI(); // query stringは含めない。
        try {
            return URI.create(path);
        } catch (IllegalArgumentException ex) {
            // firewallが拒否する未エスケープ文字を含むpathもURIとして安全に表現する。
            try {
                return new URI(null, null, path, null);
            } catch (URISyntaxException impossible) {
                throw new IllegalArgumentException("Invalid request path", impossible);
            }
        }
    }
}
