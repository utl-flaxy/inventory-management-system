package com.example.demo.controller;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.exception.ProductVersionConflictException;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import com.example.demo.service.ProductService;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ProductController.class, AdminOrderController.class})
@AutoConfigureMockMvc(addFilters = false)
class InventoryConflictControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockBean
    private ProductService productService;
    @MockBean
    private OrderService orderService;
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private UserDetailsService userDetailsService;

    static Stream<RuntimeException> conflicts() {
        return Stream.of(new ProductVersionConflictException(),
                new ObjectOptimisticLockingFailureException("Product", 1L),
                new CannotAcquireLockException("lock wait"), new OptimisticLockException("stale"),
                new PessimisticLockException("deadlock"), new LockTimeoutException("timeout"));
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void productAndOrderConcurrencyFailuresReturn409(RuntimeException conflict) throws Exception {
        when(productService.update(eq(1L), any(ProductUpdateRequest.class))).thenThrow(conflict);
        when(orderService.cancelOrder(1L)).thenThrow(conflict);

        mvc.perform(put("/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":20,\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
        mvc.perform(post("/api/v1/admin/orders/1/cancel"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"version\":null", ",\"version\":-1"})
    void missingOrInvalidUpdateVersionReturns400WithoutCallingService(String version) throws Exception {
        mvc.perform(put("/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"stock\":20" + version + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.version").exists());
        verifyNoInteractions(productService);
    }
}
