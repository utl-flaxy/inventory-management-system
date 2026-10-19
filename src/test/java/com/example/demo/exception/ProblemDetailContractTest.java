package com.example.demo.exception;

import com.example.demo.dto.OrderRequest;
import com.example.demo.dto.RegisterRequest;
import com.example.demo.entity.OrderStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProblemDetailContractTest {
    private MockMvc mvc;
    private static RuntimeException failure;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<Arguments> businessProblems() {
        return Stream.of(
            Arguments.of(new UsernameAlreadyExistsException(), 409, "USERNAME_ALREADY_EXISTS", "Username Already Exists"),
            Arguments.of(new InvalidCredentialsException(), 401, "INVALID_CREDENTIALS", "Invalid Credentials"),
            Arguments.of(new InvalidIdempotencyKeyException("invalid key"), 400, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency Key"),
            Arguments.of(new IdempotencyConflictException(), 409, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency Key Conflict"),
            Arguments.of(new AuthenticationCredentialsNotFoundException("sensitive internal"), 401, "UNAUTHENTICATED", "Unauthenticated"),
            Arguments.of(new AccessDeniedException("sensitive internal"), 403, "ACCESS_DENIED", "Access Denied"),
            Arguments.of(new ProductVersionConflictException(), 409, "CONCURRENT_UPDATE", "Concurrent Update"),
            Arguments.of(new CannotAcquireLockException("SQL password"), 409, "CONCURRENT_UPDATE", "Concurrent Update"),
            Arguments.of(new InvalidProductUpdateException(), 400, "INVALID_PRODUCT_UPDATE", "Invalid Product Update"),
            Arguments.of(new InvalidInventoryAdjustmentException(), 400, "INVALID_INVENTORY_ADJUSTMENT", "Invalid Inventory Adjustment"),
            Arguments.of(new InventoryAdjustmentConflictException(), 409, "INVENTORY_ADJUSTMENT_CONFLICT", "Inventory Adjustment Conflict"),
            Arguments.of(new IllegalOrderStateTransitionException(OrderStatus.CANCELLED, OrderStatus.PENDING), 409, "ILLEGAL_ORDER_STATE_TRANSITION", "Illegal Order State Transition"),
            Arguments.of(new OrderInventoryRestorationException("在庫の復元に失敗しました"), 409, "ORDER_INVENTORY_RESTORATION_FAILED", "Order Inventory Restoration Failed"),
            Arguments.of(new OrderNotFoundException(), 404, "ORDER_NOT_FOUND", "Order Not Found"),
            Arguments.of(new ProductNotFoundException(), 404, "PRODUCT_NOT_FOUND", "Product Not Found"),
            Arguments.of(new UserNotFoundException(), 404, "USER_NOT_FOUND", "User Not Found"),
            Arguments.of(new ProductDeleteConflictException(), 409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict"),
            Arguments.of(new InvalidOrderRequestException("注文金額が上限を超えています"), 400, "INVALID_ORDER_REQUEST", "Invalid Order Request"),
            Arguments.of(new OutOfStockException("在庫が不足しています"), 400, "OUT_OF_STOCK", "Out of Stock"),
            Arguments.of(new RequestRejectedException("sensitive firewall message"), 400, "INVALID_REQUEST", "Invalid Request"));
    }

    @ParameterizedTest
    @MethodSource("businessProblems")
    void businessStatusCodeAndStableTypeTitleRemainConsistent(RuntimeException ex, int status, String code, String title) throws Exception {
        failure = ex;
        mvc.perform(get("/probe/failure").queryParam("secret", "hidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(problem(status, code, title, "/probe/failure"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/register", "/products/1", "/api/v1/admin/orders"})
    void allUnexpected500sHideInternalDetailsIncludingOutsideAuth(String path) throws Exception {
        failure = new RuntimeException("SQL constraint uk_secret jdbc:mysql://private/db C:/private/key password hash $2a$ token JWT_SECRET");
        mvc.perform(get(path)).andExpect(problem(500, "SYSTEM_ERROR", "System Error", path))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 500})
    void servletParameterErrorsPreserveStatusAndHideParserMessages(int status) throws Exception {
        failure = new org.apache.tomcat.util.http.InvalidParameterException("raw-parameter-canary", status);
        mvc.perform(get("/probe/failure"))
                .andExpect(problem(status, status < 500 ? "INVALID_REQUEST" : "SYSTEM_ERROR",
                        status < 500 ? "Invalid Request" : "System Error", "/probe/failure"))
                .andExpect(jsonPath("$.detail").value(status < 500 ? ApiProblemFactory.INVALID_REQUEST_DETAIL
                        : ApiProblemFactory.SYSTEM_ERROR_DETAIL));
    }

    @Test
    void unrelatedIllegalStateIsStillAnInternalError() throws Exception {
        failure = new IllegalStateException("raw-parameter-canary");
        mvc.perform(get("/probe/failure"))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/probe/failure"))
                .andExpect(jsonPath("$.detail").value(ApiProblemFactory.SYSTEM_ERROR_DETAIL));
    }

    @Test
    void productNotFoundFactoryHasStableTitleTypeAndDetail() {
        var problem = ApiProblemFactory.create(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                new ProductNotFoundException().getMessage(), new MockHttpServletRequest("GET", "/products/999"));

        assertEquals("Product Not Found", ApiProblemFactory.title("PRODUCT_NOT_FOUND"));
        assertEquals("Product Not Found", problem.getTitle());
        assertEquals("https://inventory-management-system.example/problems/product-not-found", problem.getType().toString());
        assertEquals(404, problem.getStatus());
        assertEquals("商品が存在しません", problem.getDetail());
        assertEquals("/products/999", problem.getInstance().toString());
        assertEquals(java.util.Map.of("code", "PRODUCT_NOT_FOUND"), problem.getProperties());
    }

    @Test
    void nestedValidationPreservesAllFieldsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/probe/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":0,\"quantity\":0}]}"))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/probe/order"))
                .andExpect(jsonPath("$.errors['items[0].productId']").value("商品IDは正の値を入力してください"))
                .andExpect(jsonPath("$.errors['items[0].quantity']").value("数量は1以上を入力してください"));
    }

    @Test
    void validationMessagesAreDeterministicForMultipleConstraintsAndHideCredentials() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(post("/probe/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"private\"}"))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", "/probe/register"))
                .andExpect(jsonPath("$.errors.username").value("ユーザー名は必須です"))
                .andExpect(jsonPath("$.errors.password").value("パスワードは8〜72文字で入力してください"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void sameFieldSelectionDoesNotDependOnBindingErrorOrder() {
        var binding = new BeanPropertyBindingResult(new RegisterRequest(), "register");
        var first = new FieldError("register", "username", "secret-value", false, new String[]{"Size"}, null, "長さが不正");
        var second = new FieldError("register", "username", "secret-value", false, new String[]{"NotBlank"}, null, "必須です");
        binding.addError(first); binding.addError(second);
        var request = new MockHttpServletRequest("POST", "/auth/register");
        var handler = new GlobalExceptionHandler();
        var one = handler.handleValidationException(new MethodArgumentNotValidException(null, binding), request).getBody();
        var reversed = new BeanPropertyBindingResult(new RegisterRequest(), "register");
        reversed.addError(second); reversed.addError(first);
        var two = handler.handleValidationException(new MethodArgumentNotValidException(null, reversed), request).getBody();
        assertEquals(one.getProperties(), two.getProperties());
        assertEquals(java.util.Map.of("username", "必須です"), one.getProperties().get("errors"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null"})
    void malformedOrMissingBodyIsSafe400(String body) throws Exception {
        mvc.perform(post("/probe/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/probe/register"));
    }

    @Test
    void unsupportedMediaTypeIsSafe415() throws Exception {
        mvc.perform(post("/probe/register").contentType(MediaType.TEXT_PLAIN).content("secret-input"))
                .andExpect(problem(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", "/probe/register"));
    }

    @Test
    void parameterMismatchIsSafe400AndDoesNotEchoValue() throws Exception {
        mvc.perform(get("/probe/number").param("id", "private-value"))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/probe/number"))
                .andExpect(jsonPath("$.detail").value("リクエストパラメータの形式が不正です"));
    }

    @Test
    void missingHeadersAreScopedToIdempotencyOnly() throws Exception {
        mvc.perform(get("/probe/idempotency")).andExpect(problem(400, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency Key", "/probe/idempotency"));
        mvc.perform(get("/probe/header")).andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/probe/header"));
    }

    @Test
    void factoryEscapesFirewallRejectedCharactersWithoutQueryOrMutation() {
        var request = new MockHttpServletRequest("GET", "/bad path");
        request.setQueryString("password=private");
        var one = ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "safe", request);
        var two = ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "safe", request);
        one.setProperty("errors", List.of("one"));
        assertEquals("/bad%20path", two.getInstance().toASCIIString());
        assertFalse(two.getProperties().containsKey("errors"));
    }

    @Test
    void standardMvcMissingParameterKeeps400WithoutInternalMessage() throws Exception {
        mvc.perform(get("/probe/number")).andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/probe/number"))
                .andExpect(jsonPath("$.detail").value("リクエストの形式が不正です"));
    }

    @Test
    void standardMvcWrongMethodKeeps405AndAllowHeader() throws Exception {
        mvc.perform(put("/probe/register")).andExpect(problem(405, "INVALID_REQUEST", "Invalid Request", "/probe/register"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void standardMvcMissingRouteKeeps404ProblemInsteadOfGeneric500() throws Exception {
        mvc.perform(get("/probe/unknown")).andExpect(problem(404, "INVALID_REQUEST", "Invalid Request", "/probe/unknown"));
    }

    @RestController
    static class Probe {
        @GetMapping({"/probe/failure", "/auth/register", "/products/1", "/api/v1/admin/orders"})
        void fail() { throw failure; }
        @PostMapping("/probe/order") void order(@Valid @RequestBody OrderRequest body) { }
        @PostMapping("/probe/register") void register(@Valid @RequestBody RegisterRequest body) { }
        @GetMapping("/probe/number") void number(@RequestParam Long id) { }
        @GetMapping("/probe/idempotency") void idempotency(@RequestHeader("Idempotency-Key") String key) { }
        @GetMapping("/probe/header") void header(@RequestHeader("X-Required") String value) { }
    }
}
