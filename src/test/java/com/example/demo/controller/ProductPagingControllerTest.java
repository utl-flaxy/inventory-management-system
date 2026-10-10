package com.example.demo.controller;

import com.example.demo.entity.Product;
import com.example.demo.exception.ApiProblemFactory;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.ProductService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProductService.class)
class ProductPagingControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ProductRepository products;
    @MockBean OrderItemRepository items;
    @MockBean InventoryMovementRepository movements;
    @MockBean JwtUtil jwt;
    @MockBean JwtAuthenticationFilter filter;
    @MockBean UserDetailsService users;

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void defaultsAndEmptyResponseUseFixedContract(String path) throws Exception {
        emptyResults();
        mvc.perform(request(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(true));
        assertEquals(PageRequest.of(0, 20, Sort.by("id")), captured(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void explicitPageAndSizeRemainZeroBased(String path) throws Exception {
        emptyResults();
        mvc.perform(request(path).param("page", "2").param("size", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(3));
        assertEquals(PageRequest.of(2, 3, Sort.by("id")), captured(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void resolverClampsLargeSizeTo100(String path) throws Exception {
        emptyResults();
        mvc.perform(request(path).param("size", "500")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        assertEquals(100, captured(path).getPageSize());
    }

    static Stream<Arguments> allowedSorts() {
        return paths().flatMap(path -> Stream.of("id", "name", "price", "stock", "version")
                .map(property -> Arguments.of(path, property)));
    }

    @ParameterizedTest
    @MethodSource("allowedSorts")
    void allowedSortKeepsRequestedDirection(String path, String property) throws Exception {
        emptyResults();
        mvc.perform(request(path).param("sort", property + ",desc")).andExpect(status().isOk());
        var expected = Sort.by(Sort.Order.desc(property));
        if (!property.equals("id")) expected = expected.and(Sort.by("id"));
        assertEquals(expected, captured(path).getSort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void multipleSortParametersKeepOrderAndAppendId(String path) throws Exception {
        emptyResults();
        mvc.perform(request(path).param("sort", "price,asc", "name,desc")).andExpect(status().isOk());
        assertEquals(Sort.by(Sort.Order.asc("price"), Sort.Order.desc("name"), Sort.Order.asc("id")),
                captured(path).getSort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void explicitIdDirectionIsNotDuplicated(String path) throws Exception {
        emptyResults();
        mvc.perform(request(path).param("sort", "price,asc", "id,desc")).andExpect(status().isOk());
        assertEquals(Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id")), captured(path).getSort());
    }

    static Stream<Arguments> unknownSorts() {
        return paths().flatMap(path -> Stream.of("createdAt", "foo", "password", "orders", "class")
                .map(property -> Arguments.of(path, property)));
    }

    @ParameterizedTest
    @MethodSource("unknownSorts")
    void unknownSortReturnsSafe400WithoutQuery(String path, String property) throws Exception {
        mvc.perform(request(path).param("sort", "price,asc", property + ",desc"))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", path))
                .andExpect(jsonPath("$.detail").value(ApiProblemFactory.INVALID_REQUEST_DETAIL));
        verifyNoInteractions(products, items, movements);
    }

    static Stream<Arguments> invalidNumbers() {
        return paths().flatMap(path -> Stream.of(
                Arguments.of(path, "page", "-1"), Arguments.of(path, "page", "abc"),
                Arguments.of(path, "size", "0"), Arguments.of(path, "size", "-1"),
                Arguments.of(path, "size", "abc")));
    }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void invalidNumbersKeepStandardResolverFallback(String path, String parameter, String value) throws Exception {
        emptyResults();
        mvc.perform(request(path).param(parameter, value)).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
        assertEquals(PageRequest.of(0, 20, Sort.by("id")), captured(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/products", "/products/search"})
    void contentFieldsAndNonemptyPageMetadataArePreserved(String path) throws Exception {
        var product = new Product(1L, "Apple", 150L, 10);
        when(products.findAll(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(product), invocation.getArgument(0), 3));
        when(products.findByNameContaining(anyString(), any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(product), invocation.getArgument(1), 3));
        mvc.perform(request(path).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7)).andExpect(jsonPath("$.content[0].length()").value(5))
                .andExpect(jsonPath("$.content[0].id").value(1)).andExpect(jsonPath("$.content[0].name").value("Apple"))
                .andExpect(jsonPath("$.content[0].price").value(150)).andExpect(jsonPath("$.content[0].stock").value(10))
                .andExpect(jsonPath("$.content[0].version").value(0))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(false));
    }

    private static Stream<String> paths() { return Stream.of("/products", "/products/search"); }

    private MockHttpServletRequestBuilder request(String path) {
        return get(path).param("keyword", " Apple ");
    }

    private void emptyResults() {
        when(products.findAll(any(Pageable.class))).thenAnswer(i -> Page.empty(i.getArgument(0)));
        when(products.findByNameContaining(anyString(), any(Pageable.class))).thenAnswer(i -> Page.empty(i.getArgument(1)));
    }

    private Pageable captured(String path) {
        var argument = ArgumentCaptor.forClass(Pageable.class);
        if (path.equals("/products/search")) verify(products).findByNameContaining(eq(" Apple "), argument.capture());
        else verify(products).findAll(argument.capture());
        return argument.getValue();
    }
}
