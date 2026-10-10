package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 注文成功(int quantity) {

        // Arrange
        Product product = new Product();
        product.setId(1L);
        product.setName("りんご");
        product.setPrice(100);
        product.setStock(10);

        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Order order = orderService.createOrder(request);

        // Assert
        assertNotNull(order);

        assertEquals(10 - quantity, product.getStock());
        assertEquals(100 * quantity, order.getTotalPrice());

        verify(productRepository).save(product);
        verify(orderRepository).save(any(Order.class));
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertEquals(quantity, itemCaptor.getValue().getQuantity());
        assertEquals(100, itemCaptor.getValue().getPrice());
        assertSame(order, itemCaptor.getValue().getOrder());
        assertSame(product, itemCaptor.getValue().getProduct());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 不正数量では保存も在庫変更も行わない(Integer quantity) {

        // Arrange
        Product product = new Product(1L, "りんご", 100, 10);
        lenient().when(productRepository.findById(any())).thenReturn(Optional.of(product));
        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 不正商品IDでは保存も在庫変更も行わない(Long productId) {

        // Arrange
        Product product = new Product(1L, "りんご", 100, 10);
        lenient().when(productRepository.findById(any())).thenReturn(Optional.of(product));
        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(1);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository);
    }

    @Test
    void リクエスト未指定ではRepositoryを呼ばない() {
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(null));
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository);
    }

    @Test
    void 金額上限超過では保存も在庫変更も行わない() {

        // Arrange
        Product product = new Product(1L, "りんご", 2, Integer.MAX_VALUE);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(Integer.MAX_VALUE / 2 + 1);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request));
        assertEquals(Integer.MAX_VALUE, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void 金額上限ちょうどの注文は成功する() {

        // Arrange
        Product product = new Product(1L, "りんご", 1, Integer.MAX_VALUE);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OrderRequest request = new OrderRequest();
        request.setProductId(1L);
        request.setQuantity(Integer.MAX_VALUE);

        // Act
        Order order = orderService.createOrder(request);

        // Assert
        assertEquals(Integer.MAX_VALUE, order.getTotalPrice());
        assertEquals(0, product.getStock());
        verify(productRepository).save(product);
        verify(orderRepository).save(order);
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

        assertEquals(2, product.getStock());
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
