package com.example.demo.service;

import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
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

    @Mock
    private EntityManager entityManager;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void customer() {
        lenient().when(currentUser.requireUser()).thenReturn(com.example.demo.TestOrders.customer());
        lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(com.example.demo.TestOrders.customer()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void 注文成功(int quantity) {

        // Arrange
        Product product = new Product();
        product.setId(1L);
        product.setName("りんご");
        product.setPrice(100L);
        product.setStock(10);

        OrderRequest request = com.example.demo.TestOrders.request(1L, quantity);

        when(productRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(product));

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Order order = orderService.createOrder(request, com.example.demo.TestOrders.key());

        // Assert
        assertNotNull(order);

        assertEquals(10 - quantity, product.getStock());
        assertEquals(100 * quantity, order.getTotalPrice());

        verify(productRepository).save(product);
        verify(orderRepository).save(any(Order.class));
        OrderItem item = order.getOrderItems().get(0);
        assertEquals(quantity, item.getQuantity());
        assertEquals(100, item.getUnitPrice());
        assertSame(order, item.getOrder());
        assertSame(product, item.getProduct());
        verifyNoInteractions(orderItemRepository); // 明細はOrderからcascade保存
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 不正数量では保存も在庫変更も行わない(Integer quantity) {

        // Arrange
        Product product = new Product(1L, "りんご", 100L, 10);
        lenient().when(productRepository.findByIdForUpdate(any())).thenReturn(Optional.of(product));
        OrderRequest request = com.example.demo.TestOrders.request(1L, quantity);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
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
        Product product = new Product(1L, "りんご", 100L, 10);
        lenient().when(productRepository.findByIdForUpdate(any())).thenReturn(Optional.of(product));
        OrderRequest request = com.example.demo.TestOrders.request(productId, 1);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository);
    }

    @Test
    void リクエスト未指定ではRepositoryを呼ばない() {
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(null, com.example.demo.TestOrders.key()));
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository);
    }

    @Test
    void 金額上限超過では保存も在庫変更も行わない() {

        // Arrange
        Product product = new Product(1L, "りんご", Long.MAX_VALUE, 10);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        OrderRequest request = com.example.demo.TestOrders.request(1L, 2);

        // Act & Assert
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void 金額上限ちょうどの注文は成功する() {

        // Arrange
        Product product = new Product(1L, "りんご", Long.MAX_VALUE, 1);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OrderRequest request = com.example.demo.TestOrders.request(1L, 1);

        // Act
        Order order = orderService.createOrder(request, com.example.demo.TestOrders.key());

        // Assert
        assertEquals(Long.MAX_VALUE, order.getTotalPrice());
        assertEquals(0, product.getStock());
        verify(productRepository).save(product);
        verify(orderRepository).save(order);
        assertEquals(1, order.getOrderItems().size());
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    void 在庫不足() {

        // Arrange
        Product product = new Product();
        product.setId(1L);
        product.setName("りんご");
        product.setPrice(100L);
        product.setStock(2);

        OrderRequest request = com.example.demo.TestOrders.request(1L, 5);

        when(productRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(product));

        // Act & Assert
        assertThrows(
                OutOfStockException.class,
                () -> orderService.createOrder(request, com.example.demo.TestOrders.key())
        );

        assertEquals(2, product.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void 商品不存在() {

        // Arrange
        OrderRequest request = com.example.demo.TestOrders.request(999L, 1);

        when(productRepository.findByIdForUpdate(999L))
                .thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> orderService.createOrder(request, com.example.demo.TestOrders.key())
                );

        assertEquals("商品が存在しません", exception.getMessage());

        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void reverseInputLocksAllProductsInIdOrderBeforeAnyInventoryWrite() {
        Product first = new Product(1L, "apple", 100L, 10);
        Product second = new Product(2L, "orange", 300L, 5);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderRequest request = com.example.demo.TestOrders.request(new OrderItemRequest(2L, 1), new OrderItemRequest(1L, 2));

        Order order = orderService.createOrder(request, com.example.demo.TestOrders.key());

        var sequence = inOrder(productRepository, entityManager, orderRepository);
        sequence.verify(productRepository).findByIdForUpdate(1L);
        sequence.verify(entityManager).refresh(first, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(productRepository).findByIdForUpdate(2L);
        sequence.verify(entityManager).refresh(second, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(productRepository).save(first);
        sequence.verify(productRepository).save(second);
        sequence.verify(orderRepository).save(order);
        assertEquals(500, order.getTotalPrice());
        assertEquals(8, first.getStock());
        assertEquals(4, second.getStock());
        assertEquals(List.of(1L, 2L), order.getOrderItems().stream().map(item -> item.getProduct().getId()).toList());
        assertEquals("alice", order.getCustomer().getUsername());
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    void duplicateProductIsRejectedBeforeReadingAnyRepositoryOrUser() {
        OrderRequest request = com.example.demo.TestOrders.request(new OrderItemRequest(1L, 2), new OrderItemRequest(1L, 3));
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository, currentUser, entityManager);
    }

    @Test
    void missingEmptyAndNullLinesAreRejectedBeforeRepositoryAccess() {
        for (List<OrderItemRequest> items : Arrays.asList(null, List.<OrderItemRequest>of(), Arrays.asList((OrderItemRequest) null))) {
            OrderRequest request = new OrderRequest();
            request.setItems(items);
            assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        }
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository, currentUser);
    }

    @Test
    void laterInvalidLineIsRejectedBeforeRepositoryAccess() {
        OrderRequest request = com.example.demo.TestOrders.request(new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 0));
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        verifyNoInteractions(productRepository, orderRepository, orderItemRepository, currentUser);
    }

    @Test
    void laterOutOfStockLineDoesNotChangeEarlierProductEvenWithoutTransactionProxy() {
        Product first = new Product(1L, "apple", 100L, 10);
        Product second = new Product(2L, "orange", 300L, 0);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        OrderRequest request = com.example.demo.TestOrders.request(new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1));
        assertThrows(OutOfStockException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        assertEquals(10, first.getStock());
        assertEquals(0, second.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    void totalSumOverflowDoesNotChangeAnyInventory() {
        Product first = new Product(1L, "apple", Long.MAX_VALUE, 10);
        Product second = new Product(2L, "orange", 1L, 10);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        OrderRequest request = com.example.demo.TestOrders.request(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1));
        assertThrows(InvalidOrderRequestException.class, () -> orderService.createOrder(request, com.example.demo.TestOrders.key()));
        assertEquals(10, first.getStock());
        assertEquals(10, second.getStock());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderItemRepository);
    }
}
