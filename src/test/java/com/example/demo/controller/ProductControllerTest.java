package com.example.demo.controller;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.exception.InvalidProductUpdateException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.exception.ProductDeleteConflictException;
import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtUtil;
import com.example.demo.service.ProductService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static com.example.demo.TestApiProblems.problem;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void 商品一覧取得成功() throws Exception {

        ProductResponse product =
                ProductResponse.builder()
                        .id(1L)
                        .name("りんご")
                        .price(300L)
                        .stock(10)
                        .build();

        Page<ProductResponse> page =
                new PageImpl<>(List.of(product));

        when(productService.findAll(any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("りんご"))
                .andExpect(jsonPath("$.content[0].price").value(300))
                .andExpect(jsonPath("$.content[0].stock").value(10));
    }

    @Test
    void 商品検索成功() throws Exception {

        ProductResponse product =
                ProductResponse.builder()
                        .id(1L)
                        .name("ゲーミングPC")
                        .price(150000L)
                        .stock(5)
                        .build();

        Page<ProductResponse> page =
                new PageImpl<>(List.of(product));

        when(productService.search(
                eq("PC"),
                any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/products/search")
                        .param("keyword", "PC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("ゲーミングPC"))
                .andExpect(jsonPath("$.content[0].price").value(150000))
                .andExpect(jsonPath("$.content[0].stock").value(5));
    }

    @Test
    void 商品詳細取得成功() throws Exception {

        ProductResponse product =
                ProductResponse.builder()
                        .id(1L)
                        .name("りんご")
                        .price(300L)
                        .stock(10)
                        .version(0L)
                        .build();

        when(productService.findById(1L))
                .thenReturn(product);

        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("りんご"))
                .andExpect(jsonPath("$.price").value(300))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void 商品登録成功() throws Exception {

        ProductRequest request = new ProductRequest();
        request.setName("バナナ");
        request.setPrice(200L);
        request.setStock(5);

        ProductResponse response =
                ProductResponse.builder()
                        .id(1L)
                        .name("バナナ")
                        .price(200L)
                        .stock(5)
                        .build();

        when(productService.save(any(ProductRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("バナナ"))
                .andExpect(jsonPath("$.price").value(200))
                .andExpect(jsonPath("$.stock").value(5));
    }

    @Test
    void 商品更新成功() throws Exception {

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setVersion(0L);
        request.setName("みかん");
        request.setPrice(150L);

        ProductResponse response =
                ProductResponse.builder()
                        .id(1L)
                        .name("みかん")
                        .price(150L)
                        .stock(10)
                        .version(1L)
                        .build();

        when(productService.update(eq(1L), any(ProductUpdateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("みかん"))
                .andExpect(jsonPath("$.price").value(150))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void 商品登録バリデーションエラー() throws Exception {

        ProductRequest request = new ProductRequest();
        request.setName("");
        request.setPrice(0L);
        request.setStock(-1);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "999", "\"10\"", "{}"})
    void stockInjectionIsRejectedBeforeService(String value) throws Exception {
        mockMvc.perform(put("/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"apple\",\"price\":100,\"version\":0,\"stock\":" + value + "}"))
                .andExpect(problem(400, "INVALID_REQUEST", "Invalid Request", "/products/1"));
        org.mockito.Mockito.verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void missingProductHasComplete404Contract(String method) throws Exception {
        stubFailure(method, new ProductNotFoundException());

        mockMvc.perform(resourceRequest(method))
                .andExpect(problem(404, "PRODUCT_NOT_FOUND", "Product Not Found", "/products/999"))
                .andExpect(jsonPath("$.detail").value("商品が存在しません"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void unexpectedServiceFailureKeepsSafe500(String method) throws Exception {
        stubFailure(method, new RuntimeException("jdbc password=private"));

        mockMvc.perform(resourceRequest(method))
                .andExpect(problem(500, "SYSTEM_ERROR", "System Error", "/products/999"))
                .andExpect(jsonPath("$.detail").value("予期しないエラーが発生しました"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void invalidUpdateServiceGuardKeeps400Contract() throws Exception {
        stubFailure("PUT", new InvalidProductUpdateException());

        mockMvc.perform(resourceRequest("PUT"))
                .andExpect(problem(400, "INVALID_PRODUCT_UPDATE", "Invalid Product Update", "/products/999"));
    }

    @ParameterizedTest(name = "{0} accepts 255 code points, case {index}")
    @MethodSource("namesAtLimit")
    void productNameBoundaryIsAcceptedWithoutChangingInput(String method, String name) throws Exception {
        var response = ProductResponse.builder().id(1L).name(name).price(100L).stock(7).version(0L).build();
        if (method.equals("POST")) when(productService.save(any(ProductRequest.class))).thenReturn(response);
        else when(productService.update(eq(1L), any(ProductUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(nameRequest(method, objectMapper.writeValueAsString(name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
        if (method.equals("POST")) verify(productService).save(argThat(request -> request.getName().equals(name)));
        else verify(productService).update(eq(1L), argThat(request -> request.getName().equals(name)));
    }

    @ParameterizedTest(name = "{0} rejects 256 code points, case {index}")
    @MethodSource("oversizedNames")
    void oversizedProductNameIsRejectedBeforeService(String method, String name) throws Exception {
        assertInvalidName(method, objectMapper.writeValueAsString(name));
    }

    @ParameterizedTest(name = "{0} rejects malformed Unicode, case {index}")
    @MethodSource("malformedNames")
    void unpairedSurrogateIsRejectedBeforeService(String method, String escapedName) throws Exception {
        assertInvalidName(method, "\"" + escapedName + "\"");
    }

    private void assertInvalidName(String method, String nameJson) throws Exception {
        String path = method.equals("POST") ? "/products" : "/products/1";
        mockMvc.perform(nameRequest(method, nameJson))
                .andExpect(problem(400, "VALIDATION_ERROR", "Validation Error", path))
                .andExpect(jsonPath("$.errors.name").isString());
        verifyNoInteractions(productService);
    }

    private MockHttpServletRequestBuilder nameRequest(String method, String nameJson) {
        return (method.equals("POST") ? post("/products") : put("/products/1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":" + nameJson + ",\"price\":100,"
                        + (method.equals("POST") ? "\"stock\":7}" : "\"version\":0}"));
    }

    static Stream<Arguments> namesAtLimit() {
        return forBothMethods(Stream.of("a".repeat(255), "あ".repeat(255), "😀".repeat(255),
                "a".repeat(254) + "😀", "e\u0301".repeat(127) + "e"));
    }

    static Stream<Arguments> oversizedNames() {
        return forBothMethods(Stream.of("a".repeat(256), "あ".repeat(256), "😀".repeat(256),
                "a".repeat(255) + "😀", "e\u0301".repeat(128)));
    }

    static Stream<Arguments> malformedNames() {
        return forBothMethods(Stream.of("\\uD83D", "\\uDE00", "\\uDE00\\uD83D", "\\uD83Da"));
    }

    private static Stream<Arguments> forBothMethods(Stream<String> names) {
        return names.flatMap(name -> Stream.of("POST", "PUT").map(method -> Arguments.of(method, name)));
    }

    private void stubFailure(String method, RuntimeException failure) {
        switch (method) {
            case "GET" -> when(productService.findById(999L)).thenThrow(failure);
            case "PUT" -> when(productService.update(eq(999L), any(ProductUpdateRequest.class))).thenThrow(failure);
            default -> doThrow(failure).when(productService).delete(999L);
        }
    }

    @Test
    void productDeleteConflictHasCompleteSafe409Contract() throws Exception {
        stubFailure("DELETE", new ProductDeleteConflictException());
        mockMvc.perform(delete("/products/999"))
                .andExpect(problem(409, "PRODUCT_DELETE_CONFLICT", "Product Delete Conflict", "/products/999"))
                .andExpect(jsonPath("$.detail").value("注文履歴または在庫履歴から参照されているため商品を削除できません"));
    }

    private MockHttpServletRequestBuilder resourceRequest(String method) {
        return switch (method) {
            case "GET" -> get("/products/999");
            case "PUT" -> put("/products/999").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"apple\",\"price\":100,\"version\":0}");
            default -> delete("/products/999");
        };
    }

    @Test
    void 商品削除成功() throws Exception {

        doNothing().when(productService).delete(1L);

        mockMvc.perform(delete("/products/1"))
                .andExpect(status().isOk());
    }
}
