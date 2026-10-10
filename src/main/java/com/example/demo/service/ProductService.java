package com.example.demo.service;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidProductUpdateException;
import com.example.demo.exception.ProductVersionConflictException;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    // 商品一覧取得（ページング対応）
    public Page<ProductResponse> findAll(Pageable pageable) {

        return productRepository.findAll(pageable)
                .map(this::toResponse);
    }

    // 商品検索（ページング対応）
    public Page<ProductResponse> search(
            String keyword,
            Pageable pageable) {

        return productRepository
                .findByNameContaining(keyword, pageable)
                .map(this::toResponse);
    }

    // 商品詳細取得
    public ProductResponse findById(Long id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("商品が存在しません"));

        return toResponse(product);
    }

    // 商品登録
    public ProductResponse save(ProductRequest request) {

        Product product = new Product();

        product.setName(request.getName());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());

        return toResponse(
                productRepository.save(product)
        );
    }

    // 商品更新
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductResponse update(Long id, ProductUpdateRequest request) {

        if (request == null || request.getVersion() == null || request.getVersion() < 0) {
            throw new InvalidProductUpdateException();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("商品が存在しません"));

        if (!request.getVersion().equals(product.getVersion())) {
            throw new ProductVersionConflictException();
        }

        product.setName(request.getName());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());

        return toResponse(
                productRepository.saveAndFlush(product)
        );
    }

    // 商品削除
    public void delete(Long id) {

        productRepository.deleteById(id);
    }

    // DTO変換
    private ProductResponse toResponse(Product product) {

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .stock(product.getStock())
                .version(product.getVersion())
                .build();
    }
}
