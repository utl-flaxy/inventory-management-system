package com.example.demo.controller;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
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

import static com.example.demo.TestOrders.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AdminOrderController.class, SelfOrderController.class})
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {
    @Autowired
    private MockMvc mvc;
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
        Order order = order(1L, OrderStatus.PENDING, OrderItem.create(new Product(1L, "apple", 100L, 10), quantity));
        when(orderService.createOrder(any())).thenReturn(order);

        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, quantity))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.totalPrice").value(100 * quantity))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderedAt").exists())
                .andExpect(jsonPath("$.customer.id").value(1))
                .andExpect(jsonPath("$.customer.username").value("alice"))
                .andExpect(jsonPath("$.customer.password").doesNotExist())
                .andExpect(jsonPath("$.customer.role").doesNotExist())
                .andExpect(jsonPath("$.items[0].productName").value("apple"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100))
                .andExpect(jsonPath("$.items[0].lineTotal").value(100 * quantity))
                .andExpect(jsonPath("$.items[0].product").doesNotExist());
        verify(orderService).createOrder(any(OrderRequest.class));
    }

    private void invalid(OrderRequest request, String field, String message) throws Exception {
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['items[0]." + field + "']").value(message));
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 数量が1未満でバリデーションエラー(int quantity) throws Exception {
        invalid(request(1L, quantity), "quantity", "数量は1以上を入力してください");
    }

    @Test
    void 数量未指定でバリデーションエラー() throws Exception {
        invalid(request(1L, null), "quantity", "数量は必須です");
    }

    @Test
    void 商品ID未指定でバリデーションエラー() throws Exception {
        invalid(request(null, 1), "productId", "商品IDは必須です");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 商品IDが正数以外でバリデーションエラー(long productId) throws Exception {
        invalid(request(productId, 1), "productId", "商品IDは正の値を入力してください");
    }

    @Test
    void 金額上限超過の業務エラーは400() throws Exception {
        when(orderService.createOrder(any())).thenThrow(new InvalidOrderRequestException("注文金額が上限を超えています"));
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2147483647}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[{\"productId\":1,\"quantity\":2147483648}]}",
            "{\"items\":[{\"productId\":9223372036854775808,\"quantity\":1}]}"
    })
    void 型の上限超過は400でServiceを呼ばない(String json) throws Exception {
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[]}", "{\"items\":[null]}",
            "{\"productId\":1,\"quantity\":1}"})
    void missingEmptyOrNullItemsAndLegacyInputReturn400(String json) throws Exception {
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void laterInvalidLineIsValidatedBeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/me/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2},{\"productId\":2,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['items[1].quantity']").exists());
        verifyNoInteractions(orderService);
    }

    @Test
    void detailAndListUseTheSameSnapshotResponse() throws Exception {
        Product product = new Product(1L, "apple", 100L, 10);
        Order order = order(1L, OrderStatus.PENDING, OrderItem.create(product, 2));
        product.setName("renamed");
        product.setPrice(900L);
        when(orderService.findById(1L)).thenReturn(order);
        when(orderService.findAll()).thenReturn(java.util.List.of(order));
        mvc.perform(get("/api/v1/admin/orders/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("apple"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100))
                .andExpect(jsonPath("$.totalPrice").value(200));
        mvc.perform(get("/api/v1/admin/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].items[0].productName").value("apple"));
    }
}
