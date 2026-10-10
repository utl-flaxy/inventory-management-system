package com.example.demo.entity;

import com.example.demo.exception.OutOfStockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OrderInvariantTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 不正数量で在庫を減算できない(Integer quantity) {
        Product product = new Product(1L, "りんご", 100, 10);

        assertThrows(IllegalArgumentException.class, () -> product.decreaseStock(quantity));
        assertEquals(10, product.getStock());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 不正在庫はSetterとConstructorとBuilderで拒否(Integer stock) {
        Product product = new Product(1L, "りんご", 100, 10);

        assertThrows(IllegalArgumentException.class, () -> product.setStock(stock));
        assertThrows(IllegalArgumentException.class, () -> new Product(1L, "りんご", 100, stock));
        assertThrows(IllegalArgumentException.class, () -> Product.builder().stock(stock).build());
        assertEquals(10, product.getStock());
    }

    @Test
    void 在庫不足で在庫は変化しない() {
        Product product = new Product(1L, "りんご", 100, 2);

        assertThrows(OutOfStockException.class, () -> product.decreaseStock(3));
        assertEquals(2, product.getStock());
    }

    @Test
    void 在庫全量の減算はゼロになる() {
        Product product = new Product(1L, "りんご", 100, 3);

        product.decreaseStock(3);

        assertEquals(0, product.getStock());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 不正明細数量はSetterとConstructorで拒否(Integer quantity) {
        OrderItem item = new OrderItem();
        item.setQuantity(1);

        assertThrows(IllegalArgumentException.class, () -> item.setQuantity(quantity));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(null, quantity, 100, null, null));
        assertEquals(1, item.getQuantity());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, Integer.MAX_VALUE})
    void 正の明細数量は設定できる(int quantity) {
        OrderItem item = new OrderItem();

        item.setQuantity(quantity);

        assertEquals(quantity, item.getQuantity());
    }
}
