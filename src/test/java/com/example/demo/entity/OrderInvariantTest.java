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
        Product product = new Product(1L, "りんご", 100L, 10);

        assertThrows(IllegalArgumentException.class, () -> product.decreaseStock(quantity));
        assertEquals(10, product.getStock());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 不正在庫はConstructorとBuilderで拒否し公開Setterを持たない(Integer stock) {
        Product product = new Product(1L, "りんご", 100L, 10);

        assertTrue(java.util.Arrays.stream(Product.class.getMethods()).noneMatch(method -> method.getName().equals("setStock")));
        assertThrows(IllegalArgumentException.class, () -> new Product(1L, "りんご", 100L, stock));
        assertThrows(IllegalArgumentException.class, () -> Product.builder().stock(stock).build());
        assertEquals(10, product.getStock());
    }

    @Test
    void 在庫不足で在庫は変化しない() {
        Product product = new Product(1L, "りんご", 100L, 2);

        assertThrows(OutOfStockException.class, () -> product.decreaseStock(3));
        assertEquals(2, product.getStock());
    }

    @Test
    void 在庫全量の減算はゼロになる() {
        Product product = new Product(1L, "りんご", 100L, 3);

        product.decreaseStock(3);

        assertEquals(0, product.getStock());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 不正明細数量はFactoryで拒否し既存明細は不変(Integer quantity) {
        Product product = new Product(1L, "りんご", 100L, 10);
        OrderItem item = OrderItem.create(product, 1);
        assertThrows(IllegalArgumentException.class, () -> OrderItem.create(product, quantity));
        assertEquals(1, item.getQuantity());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, Integer.MAX_VALUE})
    void 正の明細数量は設定できる(int quantity) {
        OrderItem item = OrderItem.create(new Product(1L, "りんご", 100L, 10), quantity);

        assertEquals(quantity, item.getQuantity());
    }
}
