package com.example.demo.exception;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDuplicateUsername(UsernameAlreadyExistsException ex) {
        return Map.of("code", "USERNAME_ALREADY_EXISTS", "message", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> handleInvalidCredentials(InvalidCredentialsException ex) {
        return Map.of("code", "INVALID_CREDENTIALS", "message", ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Map<String, String> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return Map.of("code", "UNSUPPORTED_MEDIA_TYPE", "message", "Content-Typeがサポートされていません");
    }

    // header解決時のSecurity firewallによる改行/control拒否も500にしない。
    @ExceptionHandler(RequestRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleRejectedRequest(RequestRejectedException ex) {
        return Map.of("code", "INVALID_REQUEST", "message", "リクエストの形式が不正です");
    }

    @ExceptionHandler({InvalidIdempotencyKeyException.class, MissingRequestHeaderException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidIdempotencyKey(Exception ex) {
        return Map.of("code", "INVALID_IDEMPOTENCY_KEY", "message", "有効なIdempotency-Keyヘッダは必須です");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return Map.of("code", "IDEMPOTENCY_KEY_CONFLICT", "message", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidParameter(MethodArgumentTypeMismatchException ex) {
        return Map.of("code", "INVALID_REQUEST", "message", "リクエストパラメータの形式が不正です");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleAccessDenied(AccessDeniedException ex) {
        return Map.of("code", "ACCESS_DENIED", "message", "この操作を実行する権限がありません");
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> handleMissingAuthentication(AuthenticationCredentialsNotFoundException ex) {
        return Map.of("code", "UNAUTHENTICATED", "message", ex.getMessage());
    }

    @ExceptionHandler({ProductVersionConflictException.class, ConcurrencyFailureException.class,
            OptimisticLockException.class, PessimisticLockException.class, LockTimeoutException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConcurrentUpdate(Exception ex) {
        return Map.of("code", "CONCURRENT_UPDATE", "message",
                "商品または注文が他の処理で更新されています。最新の情報で再試行してください");
    }

    @ExceptionHandler(InvalidProductUpdateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidProductUpdate(InvalidProductUpdateException ex) {
        return Map.of("code", "INVALID_PRODUCT_UPDATE", "message", ex.getMessage());
    }

    @ExceptionHandler(IllegalOrderStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleIllegalOrderStateTransition(IllegalOrderStateTransitionException ex) {
        return Map.of("code", "ILLEGAL_ORDER_STATE_TRANSITION", "message", ex.getMessage());
    }

    @ExceptionHandler(OrderInventoryRestorationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleOrderInventoryRestoration(OrderInventoryRestorationException ex) {
        return Map.of("code", "ORDER_INVENTORY_RESTORATION_FAILED", "message", ex.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleOrderNotFound(OrderNotFoundException ex) {
        return Map.of("code", "ORDER_NOT_FOUND", "message", ex.getMessage());
    }

    // Integer/Longの範囲外を含むJSON入力エラー
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return Map.of("code", "INVALID_REQUEST", "message", "リクエストの形式が不正です");
    }

    // 注文入力の業務制約エラー
    @ExceptionHandler(InvalidOrderRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidOrderRequestException(
            InvalidOrderRequestException ex) {

        return Map.of("code", "INVALID_ORDER_REQUEST", "message", ex.getMessage());
    }

    // 在庫不足
    @ExceptionHandler(OutOfStockException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleOutOfStockException(
            OutOfStockException ex) {

        Map<String, String> error = new HashMap<>();
        error.put("code", "OUT_OF_STOCK");
        error.put("message", ex.getMessage());

        return error;
    }

    // Validationエラー
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidationException(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        ));

        return errors;
    }

    // その他の例外
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleException(
            Exception ex, HttpServletRequest request) {

        Map<String, String> error = new HashMap<>();
        error.put("code", "SYSTEM_ERROR");
        // Authだけは予期しない内部例外も伏せる。他APIの既存エラー契約は変更しない。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        error.put("message", path.equals("/auth/register") || path.equals("/auth/login")
                ? "認証処理中にエラーが発生しました" : ex.getMessage());

        return error;
    }
}
