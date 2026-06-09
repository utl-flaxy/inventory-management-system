package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.Product;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void 注文成功() {

        // Arrange
        Product product = new Product();
        product.setId(1L);
        product.setName("りんご");
        product.setPrice(100);
        product.setStock(10);

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(3);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Order order = orderService.createOrder(request);

        // Assert
        assertNotNull(order);

        // 在庫が10→7になっているか
        assertEquals(7, product.getStock());

        verify(productRepository).save(product);
        verify(orderRepository).save(any(Order.class));
        verify(orderItemRepository).save(any());
    }

    @Test
    void 在庫不足() {

        // Arrange
        Product product = new Product();
        product.setId(1L);
        product.setName("りんご");
        product.setPrice(100);
        product.setStock(2);

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(5);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        // Act & Assert
        assertThrows(
                OutOfStockException.class,
                () -> orderService.createOrder(request)
        );

        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void 商品不存在() {

        // Arrange
        OrderRequest request = new OrderRequest();
        request.setProductId(999L);
        request.setQuantity(1);

        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> orderService.createOrder(request)
                );

        assertEquals("商品が存在しません", exception.getMessage());

        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }
}