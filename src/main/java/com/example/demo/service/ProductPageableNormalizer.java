package com.example.demo.service;

import com.example.demo.exception.InvalidProductPageRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

final class ProductPageableNormalizer {
    private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "price", "stock", "version");
    private static final Sort ID_ASC = Sort.by(Sort.Direction.ASC, "id");

    private ProductPageableNormalizer() {
    }

    static Pageable normalize(Pageable pageable) {
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORTS.contains(order.getProperty())) {
                throw new InvalidProductPageRequestException();
            }
        }
        // 同値の商品でもページ間の順序を固定する。明示されたidの方向は尊重する。
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(ID_ASC);
        }
        // HTTP resolverと同じ上限をServiceの直接呼び出しにも適用する。
        return PageRequest.of(pageable.isPaged() ? pageable.getPageNumber() : 0,
                pageable.isPaged() ? Math.min(pageable.getPageSize(), 100) : 20, sort);
    }
}
