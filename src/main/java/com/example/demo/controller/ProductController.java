package com.example.demo.controller;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // 商品一覧取得（ログイン済みユーザーなら誰でも）
    @GetMapping
    public List<ProductResponse> getAll() {

        return productService.findAll();
    }

    // 商品詳細取得（ログイン済みユーザーなら誰でも）
    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {

        return productService.findById(id);
    }

    // 商品登録（ADMINのみ）
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ProductResponse create(
            @Valid @RequestBody ProductRequest request) {

        return productService.save(request);
    }

    // 商品更新（ADMINのみ）
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ProductResponse update(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {

        return productService.update(id, request);
    }

    // 商品削除（ADMINのみ）
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {

        productService.delete(id);
    }
}