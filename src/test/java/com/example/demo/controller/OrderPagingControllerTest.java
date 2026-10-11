package com.example.demo.controller;

import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.OrderService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AdminOrderController.class, SelfOrderController.class})
@AutoConfigureMockMvc(addFilters = false)
class OrderPagingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;
    @MockitoBean JwtAuthenticationFilter filter;
    @MockitoBean JwtUtil jwt;
    @MockitoBean UserDetailsService users;
    private static final String ADMIN = "/api/v1/admin/orders", ME = "/api/v1/me/orders";

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void defaultsReturnOnlyTheExistingPageResponseFields(String path) throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.Direction.DESC, "id");
        stub(path, pageable);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().json(
                "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}",
                org.springframework.test.json.JsonCompareMode.STRICT));
        verifyPage(path, pageable);
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(ADMIN, ME).flatMap(path -> Stream.of(
                Arguments.of(path, "page", "-1"), Arguments.of(path, "page", ""),
                Arguments.of(path, "page", "abc"), Arguments.of(path, "page", "2147483647"),
                Arguments.of(path, "size", "0"), Arguments.of(path, "size", "-1"),
                Arguments.of(path, "size", ""), Arguments.of(path, "size", "abc"),
                Arguments.of(path, "size", "2147483648"), Arguments.of(path, "sort", "customer.password,asc"),
                Arguments.of(path, "sort", "items,desc"), Arguments.of(path, "sort", "id,sideways"),
                Arguments.of(path, "sort", "id"), Arguments.of(path, "sort", "")));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidParametersAreSafe400BeforeService(String path, String parameter, String value) throws Exception {
        mvc.perform(get(path).param(parameter, value)).andExpect(status().isBadRequest())
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", path));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void size500IsCappedAndMultipleSortsKeepExplicitId(String path) throws Exception {
        var pageable = PageRequest.of(2, 100, Sort.by(Sort.Order.asc("status"), Sort.Order.desc("totalPrice"), Sort.Order.asc("id")));
        stub(path, pageable);
        mvc.perform(get(path).param("page", "2").param("size", "500")
                        .param("sort", "status,asc", "totalPrice,desc", "id,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.page").value(2));
        verifyPage(path, pageable);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void missingIdSortGetsDeterministicDescendingTieBreaker(String path) throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.by("orderedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        stub(path, pageable);
        mvc.perform(get(path).param("sort", "orderedAt,asc")).andExpect(status().isOk());
        verifyPage(path, pageable);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ME})
    void duplicateIdSortIs400(String path) throws Exception {
        mvc.perform(get(path).param("sort", "id,asc", "id,desc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    private void stub(String path, Pageable pageable) {
        if (path.equals(ADMIN)) when(service.findAll(pageable)).thenReturn(Page.empty(pageable));
        else when(service.findMyOrders(pageable)).thenReturn(Page.empty(pageable));
    }

    private void verifyPage(String path, Pageable pageable) {
        if (path.equals(ADMIN)) verify(service).findAll(pageable);
        else verify(service).findMyOrders(pageable);
        verifyNoMoreInteractions(service);
    }
}
