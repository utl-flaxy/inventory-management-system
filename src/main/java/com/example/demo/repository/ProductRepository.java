package com.example.demo.repository;

import com.example.demo.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // 商品名の部分一致検索（ページング対応）
    Page<Product> findByNameContaining(
            String keyword,
            Pageable pageable
    );

}