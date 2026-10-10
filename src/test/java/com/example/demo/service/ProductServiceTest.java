package com.example.demo.service;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.exception.ProductDeleteConflictException;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock private ProductRepository repository;
    @Mock private OrderItemRepository orderItems;
    @Mock private InventoryMovementRepository movements;
    @InjectMocks private ProductService service;

    @Test
    void missingDetailOnlyLooksUpProduct() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> service.findById(999L));

        verify(repository).findById(999L);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 123})
    void missingUpdateIsNotFoundRegardlessOfValidVersionAndNeverWrites(long version) {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> service.update(999L, request(version)));

        verify(repository).findById(999L);
        // save/saveAndFlush/flush等の書き込みも、追加lookupも一切行わない。
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingDeleteOnlyLooksUpProductAndNeverDeletes() {
        when(repository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> service.delete(999L));

        verify(repository).findByIdForUpdate(999L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(orderItems, movements);
    }

    @Test
    void existingDetailKeepsResponseAndDoesNotWrite() {
        Product product = new Product(1L, "apple", 100L, 10);
        when(repository.findById(1L)).thenReturn(Optional.of(product));

        var response = service.findById(1L);

        assertEquals(1L, response.getId());
        assertEquals("apple", response.getName());
        assertEquals(100L, response.getPrice());
        assertEquals(10, response.getStock());
        assertEquals(0L, response.getVersion());
        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void existingDeleteDeletesTheFoundEntity() {
        Product product = new Product(1L, "apple", 100L, 10);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        service.delete(1L);

        var calls = inOrder(repository, orderItems, movements);
        calls.verify(repository).findByIdForUpdate(1L);
        calls.verify(orderItems).existsByProductId(1L);
        calls.verify(movements).existsByProductId(1L);
        calls.verify(repository).delete(product);
        calls.verify(repository).flush();
        verifyNoMoreInteractions(repository, orderItems, movements);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void repositoryLookupFailureIsNeverReclassifiedAsNotFound(String method) {
        var failure = new DataIntegrityViolationException("unexpected repository failure");
        if (method.equals("DELETE")) when(repository.findByIdForUpdate(1L)).thenThrow(failure);
        else when(repository.findById(1L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            switch (method) {
                case "GET" -> service.findById(1L);
                case "PUT" -> service.update(1L, request(0));
                default -> service.delete(1L);
            }
        });

        assertSame(failure, thrown);
        if (method.equals("DELETE")) verify(repository).findByIdForUpdate(1L);
        else verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(orderItems, movements);
    }

    @Test
    void repositoryDeleteFailureIsNeverReclassifiedAsNotFound() {
        Product product = new Product(1L, "apple", 100L, 10);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        var failure = new DataIntegrityViolationException("unexpected delete failure");
        doThrow(failure).when(repository).delete(product);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.delete(1L)));

        verify(repository).findByIdForUpdate(1L);
        verify(repository).delete(product);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "movement"})
    void referenceCheckRejectsDeletionBeforeDeleteOrFlush(String reference) {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        if (reference.equals("order")) when(orderItems.existsByProductId(1L)).thenReturn(true);
        else when(movements.existsByProductId(1L)).thenReturn(true);

        var failure = assertThrows(ProductDeleteConflictException.class, () -> service.delete(1L));

        assertEquals("注文履歴または在庫履歴から参照されているため商品を削除できません", failure.getMessage());
        var calls = inOrder(repository, orderItems, movements);
        calls.verify(repository).findByIdForUpdate(1L);
        calls.verify(orderItems).existsByProductId(1L);
        if (reference.equals("movement")) calls.verify(movements).existsByProductId(1L);
        verifyNoMoreInteractions(repository, orderItems, movements);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fk_order_items_product", "fk_inventory_movement_product"})
    void flushTranslatesOnlyKnownMariaDbDeleteForeignKeysThroughNestedCauses(String constraint) {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        doThrow(new DataIntegrityViolationException("SQL password=private",
                new IllegalStateException("wrapper", new SQLException(foreignKeyMessage(constraint), "23000", 1451))))
                .when(repository).flush();

        var failure = assertThrows(ProductDeleteConflictException.class, () -> service.delete(1L));

        assertEquals("注文履歴または在庫履歴から参照されているため商品を削除できません", failure.getMessage());
        assertNull(failure.getCause());
        verify(repository).delete(any(Product.class));
        verify(repository).flush();
    }

    static Stream<Arguments> unrelatedFailures() {
        return Stream.of(
                Arguments.of(1452, foreignKeyMessage("fk_order_items_product")),
                Arguments.of(1062, foreignKeyMessage("fk_inventory_movement_product")),
                Arguments.of(1451, foreignKeyMessage("fk_inventory_movement_order")),
                Arguments.of(1451, foreignKeyMessage("fk_order_items_product_suffix")),
                Arguments.of(1451, foreignKeyMessage("prefix_fk_inventory_movement_product")),
                Arguments.of(1451, "unstructured fk_order_items_product"),
                Arguments.of(1451, foreignKeyMessage("unknown") + " mentions fk_order_items_product"),
                Arguments.of(1451, (String) null));
    }

    @ParameterizedTest
    @MethodSource("unrelatedFailures")
    void unrelatedFlushFailureIsRethrownUnchanged(int errorCode, String message) {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        var failure = new DataIntegrityViolationException("SQL password=private", new SQLException(message, "23000", errorCode));
        doThrow(failure).when(repository).flush();

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.delete(1L)));
    }

    @Test
    void integrityFailureWithoutSqlCauseIsNotBusinessConflict() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(new Product(1L, "apple", 100L, 10)));
        var failure = new DataIntegrityViolationException(foreignKeyMessage("fk_order_items_product"));
        doThrow(failure).when(repository).flush();
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.delete(1L)));
    }

    private static String foreignKeyMessage(String constraint) {
        return "Cannot delete or update a parent row: a foreign key constraint fails "
                + "(db.table, CONSTRAINT `" + constraint + "` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`))";
    }

    private ProductUpdateRequest request(long version) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setName("orange");
        request.setPrice(200L);
        request.setVersion(version);
        return request;
    }
}
