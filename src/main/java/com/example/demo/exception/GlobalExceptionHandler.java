package com.example.demo.exception;

import jakarta.persistence.LockTimeoutException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@RestControllerAdvice
@Slf4j
@Hidden // OpenAPIのerror契約は共通customizerで定義する。
public class GlobalExceptionHandler {

    @ExceptionHandler(LoginSourceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleLoginSource(LoginSourceUnavailableException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.SERVICE_UNAVAILABLE, "LOGIN_SOURCE_UNAVAILABLE",
                LoginSourceUnavailableException.DETAIL, request);
    }

    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleLoginRateLimit(LoginRateLimitExceededException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblemFactory.create(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                        LoginRateLimitExceededException.DETAIL, request));
    }

    @ExceptionHandler(OrderCsvExportException.class)
    public ResponseEntity<ProblemDetail> handleCsvExport(OrderCsvExportException ex,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        log.atError().addKeyValue("event", "system.unhandled_error")
                .addKeyValue("exceptionType", ex.failureType()).addKeyValue("rootCauseType", ex.rootCauseType())
                .addKeyValue("topFrame", ex.topFrame()).log("Unexpected system error");
        // 途中のCSVにJSONを混ぜずcontainerへ中断を伝える。例外自体はsafe messageのみ。
        if (response.isCommitted()) throw ex;
        response.resetBuffer();
        response.setCharacterEncoding((String) null);
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, null);
        return ApiProblemFactory.response(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR",
                ApiProblemFactory.SYSTEM_ERROR_DETAIL, request);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateUsername(UsernameAlreadyExistsException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Content-Typeがサポートされていません", request);
    }

    @ExceptionHandler(RequestRejectedException.class)
    public ResponseEntity<ProblemDetail> handleRejectedRequest(RequestRejectedException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ApiProblemFactory.INVALID_REQUEST_DETAIL, request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ProblemDetail> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "有効なIdempotency-Keyヘッダは必須です", request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleInvalidParameter(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "リクエストパラメータの形式が不正です", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ApiProblemFactory.ACCESS_DENIED_DETAIL, request);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleMissingAuthentication(AuthenticationCredentialsNotFoundException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ApiProblemFactory.UNAUTHENTICATED_DETAIL, request);
    }

    @ExceptionHandler(InvalidProductUpdateException.class)
    public ResponseEntity<ProblemDetail> handleInvalidProductUpdate(InvalidProductUpdateException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_UPDATE", ex.getMessage(), request);
    }

    @ExceptionHandler({InvalidProductPageRequestException.class, InvalidProductSearchKeywordException.class,
            InvalidOrderPageRequestException.class})
    public ResponseEntity<ProblemDetail> handleInvalidPageRequest(RuntimeException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ApiProblemFactory.INVALID_REQUEST_DETAIL, request);
    }

    @ExceptionHandler(IllegalOrderStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleIllegalOrderStateTransition(IllegalOrderStateTransitionException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "ILLEGAL_ORDER_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderInventoryRestorationException.class)
    public ResponseEntity<ProblemDetail> handleOrderInventoryRestoration(OrderInventoryRestorationException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "ORDER_INVENTORY_RESTORATION_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ProductDeleteConflictException.class)
    public ResponseEntity<ProblemDetail> handleProductDeleteConflict(ProductDeleteConflictException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "PRODUCT_DELETE_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(InventoryAdjustmentConflictException.class)
    public ResponseEntity<ProblemDetail> handleInventoryConflict(InventoryAdjustmentConflictException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "INVENTORY_ADJUSTMENT_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidInventoryAdjustmentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidInventory(InvalidInventoryAdjustmentException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_ADJUSTMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequest(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ApiProblemFactory.INVALID_REQUEST_DETAIL, request);
    }

    @ExceptionHandler(InvalidOrderRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidOrderRequestException(InvalidOrderRequestException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_ORDER_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ProblemDetail> handleOutOfStockException(OutOfStockException ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "OUT_OF_STOCK", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                        "有効なIdempotency-Keyヘッダは必須です", request)
                : ApiProblemFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                        ApiProblemFactory.INVALID_REQUEST_DETAIL, request);
    }

    @ExceptionHandler({ProductVersionConflictException.class, ConcurrencyFailureException.class,
            OptimisticLockException.class, PessimisticLockException.class, LockTimeoutException.class})
    public ResponseEntity<ProblemDetail> handleConcurrentUpdate(Exception ex, HttpServletRequest request) {
        return ApiProblemFactory.response(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "商品または注文が他の処理で更新されています。最新の情報で再試行してください", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        Map<String, String> errors = new TreeMap<>();
        // 同一fieldはconstraint code→message順の先頭だけ採用。rejectedValueは返さない。
        ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(error -> Objects.toString(error.getCode(), ""))
                        .thenComparing(error -> Objects.toString(error.getDefaultMessage(), "")))
                .forEach(error -> errors.putIfAbsent(error.getField(),
                        Objects.toString(error.getDefaultMessage(), "入力値が不正です")));
        ProblemDetail problem = ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "入力内容にエラーがあります", request);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception ex, HttpServletRequest request) {
        // MVC標準の4xxもsafe detailで統一する。内部ErrorResponseのbody/messageは使わない。
        if (ex instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiProblemFactory.create(error.getStatusCode(), "INVALID_REQUEST",
                            ApiProblemFactory.INVALID_REQUEST_DETAIL, request));
        }
        Throwable root = rootCause(ex);
        StackTraceElement[] frames = root.getStackTrace();
        log.atError().addKeyValue("event", "system.unhandled_error")
                .addKeyValue("exceptionType", ex.getClass().getName())
                .addKeyValue("rootCauseType", root.getClass().getName())
                .addKeyValue("topFrame", frames.length == 0 ? "unknown" : frames[0].toString())
                .log("Unexpected system error");
        return ApiProblemFactory.response(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR",
                ApiProblemFactory.SYSTEM_ERROR_DETAIL, request);
    }

    private Throwable rootCause(Throwable exception) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable root = exception;
        visited.add(root);
        while (root.getCause() != null && visited.add(root.getCause())) {
            root = root.getCause();
        }
        return root;
    }
}
