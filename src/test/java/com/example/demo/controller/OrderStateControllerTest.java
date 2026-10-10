package com.example.demo.controller;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.IllegalOrderStateTransitionException;
import com.example.demo.exception.OrderInventoryRestorationException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderStateControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private OrderService orderService;
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private UserDetailsService userDetailsService;

    private MockHttpServletRequestBuilder request(String operation) {
        return switch (operation) {
            case "legacy-cancel" -> patch("/api/v1/admin/orders/1/cancel");
            case "status" -> patch("/api/v1/admin/orders/1/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"CANCELLED\"}");
            default -> post("/api/v1/admin/orders/1/" + operation);
        };
    }

    private OrderStatus target(String operation) {
        return switch (operation) {
            case "ship" -> OrderStatus.SHIPPED;
            case "complete" -> OrderStatus.COMPLETED;
            default -> OrderStatus.CANCELLED;
        };
    }

    private void fail(String operation, RuntimeException exception) {
        switch (operation) {
            case "ship" -> when(orderService.shipOrder(1L)).thenThrow(exception);
            case "complete" -> when(orderService.completeOrder(1L)).thenThrow(exception);
            case "status" -> when(orderService.updateStatus(1L, OrderStatus.CANCELLED)).thenThrow(exception);
            default -> when(orderService.cancelOrder(1L)).thenThrow(exception);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "legacy-cancel", "ship", "complete"})
    void commandEndpointsCallTheAppropriateService(String operation) throws Exception {
        Order order = responseOrder(target(operation));
        switch (operation) {
            case "ship" -> when(orderService.shipOrder(1L)).thenReturn(order);
            case "complete" -> when(orderService.completeOrder(1L)).thenReturn(order);
            default -> when(orderService.cancelOrder(1L)).thenReturn(order);
        }

        mockMvc.perform(request(operation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(target(operation).name()));

        switch (operation) {
            case "ship" -> verify(orderService).shipOrder(1L);
            case "complete" -> verify(orderService).completeOrder(1L);
            default -> verify(orderService).cancelOrder(1L);
        }
        verifyNoMoreInteractions(orderService);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "COMPLETED", "CANCELLED"})
    void legacyStatusApiValidatesAndPassesTheTargetToService(OrderStatus target) throws Exception {
        when(orderService.updateStatus(1L, target)).thenReturn(responseOrder(target));

        mockMvc.perform(patch("/api/v1/admin/orders/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + target.name() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(target.name()));

        verify(orderService).updateStatus(1L, target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "legacy-cancel", "ship", "complete", "status"})
    void illegalTransitionReturns409(String operation) throws Exception {
        fail(operation, new IllegalOrderStateTransitionException(OrderStatus.CANCELLED, target(operation)));

        mockMvc.perform(request(operation)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_ORDER_STATE_TRANSITION"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "legacy-cancel", "ship", "complete", "status"})
    void missingOrderReturns404(String operation) throws Exception {
        fail(operation, new OrderNotFoundException());

        mockMvc.perform(request(operation)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}"})
    void missingOrNullStatusReturns400WithoutCallingService(String json) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/1/status").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("注文ステータスは必須です"));

        verifyNoInteractions(orderService);
    }

    @Test
    void unknownStatusReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void restorationOverflowReturns409() throws Exception {
        when(orderService.cancelOrder(1L)).thenThrow(new OrderInventoryRestorationException("在庫数の上限超過"));

        mockMvc.perform(post("/api/v1/admin/orders/1/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_INVENTORY_RESTORATION_FAILED"));
    }

    @Test
    void existingOrderDetailAlsoReturns404ForMissingOrder() throws Exception {
        when(orderService.findById(1L)).thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/api/v1/admin/orders/1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private Order responseOrder(OrderStatus status) {
        return com.example.demo.TestOrders.order(1L, status,
                OrderItem.create(new Product(1L, "apple", 100L, 10), 5));
    }
}
