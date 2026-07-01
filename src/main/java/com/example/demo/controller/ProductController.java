package com.example.demo.controller;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.service.ProductService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // 商品一覧取得（ページング対応）
    @GetMapping
    public Page<ProductResponse> getAll(
            @ParameterObject Pageable pageable) {

        return productService.findAll(pageable);
    }

    // 商品検索（ページング対応）
    @GetMapping("/search")
    public Page<ProductResponse> search(
            @RequestParam String keyword,
            @ParameterObject Pageable pageable) {

        return productService.search(
                keyword,
                pageable
        );
    }

    // 商品詳細取得
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