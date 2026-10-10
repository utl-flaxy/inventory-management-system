package com.example.demo.service;

import com.example.demo.dto.ProductRequest;
import com.example.demo.dto.ProductResponse;
import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidProductUpdateException;
import com.example.demo.exception.ProductDeleteConflictException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.exception.ProductVersionConflictException;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Set<String> PRODUCT_REFERENCE_CONSTRAINTS =
            Set.of("fk_order_items_product", "fk_inventory_movement_product");
    private static final Pattern FOREIGN_KEY_CONSTRAINT =
            Pattern.compile("\\bCONSTRAINT\\s+`([^`]+)`\\s+FOREIGN KEY\\b");

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

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

        Product product = findProductOrThrow(id);

        return toResponse(product);
    }

    // 商品登録
    public ProductResponse save(ProductRequest request) {

        Product product = new Product(null, request.getName(), request.getPrice(), request.getStock());

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

        Product product = findProductOrThrow(id);

        if (!request.getVersion().equals(product.getVersion())) {
            throw new ProductVersionConflictException();
        }

        product.setName(request.getName());
        product.setPrice(request.getPrice());

        return toResponse(
                productRepository.saveAndFlush(product)
        );
    }

    // 商品削除
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long id) {

        Product product = productRepository.findByIdForUpdate(id).orElseThrow(ProductNotFoundException::new);
        if (orderItemRepository.existsByProductId(id) || inventoryMovementRepository.existsByProductId(id)) {
            throw new ProductDeleteConflictException();
        }
        try {
            productRepository.delete(product);
            // SQLをこのTransaction内で実行し、commit後ではなくここで対象FK違反を変換する。
            productRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (isProductReferenceViolation(ex)) throw new ProductDeleteConflictException();
            throw ex;
        }
    }

    // MariaDB delete FK違反(1451)と、driver messageの正確な制約名を両方確認する。
    private boolean isProductReferenceViolation(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1451 && sql.getMessage() != null) {
                var constraint = FOREIGN_KEY_CONSTRAINT.matcher(sql.getMessage());
                if (constraint.find() && PRODUCT_REFERENCE_CONSTRAINTS.contains(constraint.group(1))) return true;
            }
        }
        return false;
    }

    private Product findProductOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(ProductNotFoundException::new);
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
