package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsService userDetailsService;

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 注文登録成功(int quantity) throws Exception {

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);

        Order order = new Order();
        order.setId(1L);
        order.setTotalPrice(100 * quantity);

        when(orderService.createOrder(any(OrderRequest.class)))
                .thenReturn(order);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.totalPrice").value(100 * quantity))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(orderService).createOrder(any(OrderRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 数量が1未満でバリデーションエラー(int quantity) throws Exception {

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.quantity").value("数量は1以上を入力してください"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void 数量未指定でバリデーションエラー() throws Exception {

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.quantity").value("数量は必須です"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void 商品ID未指定でバリデーションエラー() throws Exception {

        OrderRequest request = new OrderRequest();
        request.setQuantity(1);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.productId").value("商品IDは必須です"));

        verify(orderService, never()).createOrder(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 商品IDが正数以外でバリデーションエラー(long productId) throws Exception {

        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(1);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.productId").value("商品IDは正の値を入力してください"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void 金額上限超過の業務エラーは400() throws Exception {

        when(orderService.createOrder(any())).thenThrow(
                new InvalidOrderRequestException("注文金額が上限を超えています"));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":2147483647}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productId\":1,\"quantity\":2147483648}",
            "{\"productId\":9223372036854775808,\"quantity\":1}"
    })
    void 型の上限超過は400でServiceを呼ばない(String json) throws Exception {

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(orderService, never()).createOrder(any());
    }
}
