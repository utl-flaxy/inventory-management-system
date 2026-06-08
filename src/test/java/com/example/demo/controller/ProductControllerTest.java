package com.example.demo.controller;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    @Test
    void 商品一覧取得成功() throws Exception {

        ProductResponse product =
                ProductResponse.builder()
                        .id(1L)
                        .name("りんご")
                        .price(300)
                        .stock(10)
                        .build();

        when(productService.findAll())
                .thenReturn(List.of(product));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("りんご"))
                .andExpect(jsonPath("$[0].price").value(300))
                .andExpect(jsonPath("$[0].stock").value(10));
    }

    @Test
    void 商品詳細取得成功() throws Exception {

        ProductResponse product =
                ProductResponse.builder()
                        .id(1L)
                        .name("りんご")
                        .price(300)
                        .stock(10)
                        .build();

        when(productService.findById(1L))
                .thenReturn(product);

        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("りんご"))
                .andExpect(jsonPath("$.price").value(300))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void 商品登録成功() throws Exception {

        ProductRequest request = new ProductRequest();
        request.setName("バナナ");
        request.setPrice(200);
        request.setStock(5);

        ProductResponse response =
                ProductResponse.builder()
                        .id(1L)
                        .name("バナナ")
                        .price(200)
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

        ProductRequest request = new ProductRequest();
        request.setName("みかん");
        request.setPrice(150);
        request.setStock(20);

        ProductResponse response =
                ProductResponse.builder()
                        .id(1L)
                        .name("みかん")
                        .price(150)
                        .stock(20)
                        .build();

        when(productService.update(eq(1L), any(ProductRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("みかん"))
                .andExpect(jsonPath("$.price").value(150))
                .andExpect(jsonPath("$.stock").value(20));
    }

    @Test
    void 商品登録バリデーションエラー() throws Exception {

        ProductRequest request = new ProductRequest();
        request.setName("");
        request.setPrice(0);
        request.setStock(-1);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.price").exists())
                .andExpect(jsonPath("$.stock").exists());
    }
}