package com.example.demo.logging;

import ch.qos.logback.classic.Level;
import com.example.demo.TestApplicationLogs;
import com.example.demo.TestOrders;
import com.example.demo.controller.*;
import com.example.demo.dto.*;
import com.example.demo.entity.*;
import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.demo.TestApplicationLogs.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessEventLoggingTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final User user = new User(7L, "private_username", "private-test-hash", "ROLE_USER");
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(new AuthService(users, passwords, jwt)),
                        new SelfOrderController(orders), new AdminOrderController(orders), new InventoryController(inventory))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RequestCorrelationFilter()).build();
    }

    private void assertEvent(MockHttpServletRequestBuilder request, String name, Map<String, Object> expected) throws Exception {
        try (var logs = new TestApplicationLogs()) {
            var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
            assertEquals(1, logs.events(name).size());
            var event = logs.events(name).get(0);
            var values = new java.util.LinkedHashMap<>(expected);
            values.put("event", name);
            assertEquals(values, fields(event));
            assertEquals(Level.INFO, event.getLevel());
            assertEquals(result.getResponse().getHeader("X-Request-Id"), event.getMDCPropertyMap().get("requestId"));
            assertEquals(2, logs.events().size()); // business event + completion、二重記録なし。
            logs.assertSafe("private_username", "private-note", "private-token");
        }
    }

    @Test
    void registrationLogsOnlyCommittedUserIdAndRole() throws Exception {
        when(users.findByUsername(user.getUsername())).thenReturn(Optional.empty());
        when(passwords.encode("super-secret-password")).thenReturn("private-test-hash");
        when(users.saveAndFlush(any())).thenReturn(user);
        assertEvent(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"private_username\",\"password\":\"super-secret-password\"}"),
                "auth.register.succeeded", Map.of("userId", 7L, "role", "ROLE_USER"));
    }

    @Test
    void loginLogsOnlyUserIdAndRoleWithoutTokenOrCredentials() throws Exception {
        when(users.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        when(passwords.matches("super-secret-password", "private-test-hash")).thenReturn(true);
        when(jwt.generateToken(user.getUsername())).thenReturn("private-token");
        assertEvent(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"private_username\",\"password\":\"super-secret-password\"}"),
                "auth.login.succeeded", Map.of("userId", 7L, "role", "ROLE_USER"));
        verify(jwt, times(1)).generateToken(user.getUsername());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingUserAndWrongPasswordProduceIdenticalWarnEvents(boolean exists) throws Exception {
        when(users.findByUsername(user.getUsername())).thenReturn(exists ? Optional.of(user) : Optional.empty());
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"private_username\",\"password\":\"super-secret-password\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            assertEquals(1, logs.events("auth.login.failed").size());
            var event = logs.events("auth.login.failed").get(0);
            assertEquals(Map.of("event", "auth.login.failed"), fields(event));
            assertEquals("Login failed", event.getFormattedMessage());
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(logs.events("auth.login.succeeded").isEmpty());
            assertTrue(logs.events("system.unhandled_error").isEmpty());
            logs.assertSafe("private_username");
        }
        verifyNoInteractions(jwt);
    }

    private Order order(OrderStatus status) {
        var order = TestOrders.order(19L, status, OrderItem.create(new Product(3L, "private-product", 100L, 10), 2));
        order.getCustomer().setUsername("private_username");
        order.getCustomer().setPassword("private-test-hash");
        return order;
    }

    @Test
    void selfCreateLogsSafeResponseFieldsAfterServiceReturns() throws Exception {
        when(orders.createOrder(any(), eq("idempotency-secret-key"))).thenReturn(order(OrderStatus.PENDING));
        assertEvent(post("/api/v1/me/orders").header("Idempotency-Key", "idempotency-secret-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":3,\"quantity\":2}]}"),
                "order.create.completed", Map.of("orderId", 19L, "status", OrderStatus.PENDING, "itemCount", 1));
    }

    @Test
    void selfCancelLogsOneSafeEvent() throws Exception {
        when(orders.cancelMyOrder(19L)).thenReturn(order(OrderStatus.CANCELLED));
        assertEvent(post("/api/v1/me/orders/19/cancel"), "order.cancel.completed",
                Map.of("orderId", 19L, "status", OrderStatus.CANCELLED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "patch-cancel", "ship", "complete", "status-cancel", "status-ship", "status-complete"})
    void adminCommandsAndLegacyStatusEmitExactlyOneCorrespondingEvent(String command) throws Exception {
        String operation = command.substring(command.lastIndexOf('-') + 1);
        OrderStatus target = switch (operation) {
            case "ship" -> OrderStatus.SHIPPED;
            case "complete" -> OrderStatus.COMPLETED;
            default -> OrderStatus.CANCELLED;
        };
        MockHttpServletRequestBuilder request;
        if (command.startsWith("status-")) {
            when(orders.updateStatus(19L, target)).thenReturn(order(target));
            request = patch("/api/v1/admin/orders/19/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"" + target + "\"}");
        } else {
            switch (operation) {
                case "ship" -> when(orders.shipOrder(19L)).thenReturn(order(target));
                case "complete" -> when(orders.completeOrder(19L)).thenReturn(order(target));
                default -> when(orders.cancelOrder(19L)).thenReturn(order(target));
            }
            request = command.equals("patch-cancel") ? patch("/api/v1/admin/orders/19/cancel")
                    : post("/api/v1/admin/orders/19/" + operation);
        }
        assertEvent(request, "order." + operation + ".completed", Map.of("orderId", 19L, "status", target));
    }

    @Test
    void inventoryLogsOnlyMovementProductAndDeltaWithoutNoteOrKey() throws Exception {
        when(inventory.adjust(eq(3L), any(), eq("idempotency-secret-key"))).thenReturn(
                new InventoryMovementResponse(21L, 3L, 5, 10, 15, InventoryMovementReason.RECEIPT,
                        InventorySourceType.MANUAL, null, new OrderResponse.CustomerResponse(7L, "private_username"),
                        "private-note", Instant.now()));
        assertEvent(post("/api/v1/admin/products/3/inventory-adjustments")
                        .header("Idempotency-Key", "idempotency-secret-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"RECEIPT\",\"note\":\"private-note\"}"),
                "inventory.adjustment.completed", Map.of("movementId", 21L, "productId", 3L, "delta", 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "create", "inventory", "cancel", "ship", "complete"})
    void serviceOrCommitFailureNeverEmitsSuccess(String command) throws Exception {
        RuntimeException failure = new RuntimeException("super-secret-password SQL uk_users_username");
        MockHttpServletRequestBuilder request = switch (command) {
            case "register" -> {
                when(users.findByUsername(user.getUsername())).thenReturn(Optional.empty());
                when(users.saveAndFlush(any())).thenThrow(failure);
                yield post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"private_username\",\"password\":\"super-secret-password\"}");
            }
            case "create" -> {
                when(orders.createOrder(any(), anyString())).thenThrow(failure);
                yield post("/api/v1/me/orders").header("Idempotency-Key", "idempotency-secret-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":3,\"quantity\":2}]}");
            }
            case "inventory" -> {
                when(inventory.adjust(anyLong(), any(), anyString())).thenThrow(failure);
                yield post("/api/v1/admin/products/3/inventory-adjustments")
                        .header("Idempotency-Key", "idempotency-secret-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"RECEIPT\"}");
            }
            default -> {
                switch (command) {
                    case "ship" -> when(orders.shipOrder(19L)).thenThrow(failure);
                    case "complete" -> when(orders.completeOrder(19L)).thenThrow(failure);
                    default -> when(orders.cancelOrder(19L)).thenThrow(failure);
                }
                yield post("/api/v1/admin/orders/19/" + command);
            }
        };
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(request).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
            assertEquals(2, logs.events().size()); // 5xx + completionだけ。
            assertEquals(1, logs.events("system.unhandled_error").size());
            assertEquals(1, logs.events("http.request.completed").size());
            logs.assertSafe("private_username");
        }
    }

    @Test
    void rejectedCommandProduces409AndOnlyCompletion() throws Exception {
        when(orders.cancelOrder(19L)).thenThrow(new IllegalOrderStateTransitionException(OrderStatus.CANCELLED, OrderStatus.CANCELLED));
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(post("/api/v1/admin/orders/19/cancel")).andExpect(status().isConflict());
            assertEquals(1, logs.events().size());
            assertEquals(1, logs.events("http.request.completed").size());
            logs.assertSafe();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/orders", "/api/v1/admin/orders", "/api/v1/me/orders/19", "/api/v1/admin/orders/19"})
    void readOnlyRequestsProduceOnlyCompletion(String path) throws Exception {
        when(orders.findMyOrders()).thenReturn(List.of());
        when(orders.findAll()).thenReturn(List.of());
        when(orders.findMyOrder(19L)).thenReturn(order(OrderStatus.PENDING));
        when(orders.findById(19L)).thenReturn(order(OrderStatus.PENDING));
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(get(path)).andExpect(status().isOk());
            assertEquals(1, logs.events().size());
            assertEquals(1, logs.events("http.request.completed").size());
            logs.assertSafe("private_username");
        }
    }
}
