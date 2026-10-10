package com.example.demo.exception;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
            Exception ex) {

        Map<String, String> error = new HashMap<>();
        error.put("code", "SYSTEM_ERROR");
        error.put("message", ex.getMessage());

        return error;
    }
}
