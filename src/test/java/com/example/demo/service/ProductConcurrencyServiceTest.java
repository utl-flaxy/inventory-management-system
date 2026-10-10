package com.example.demo.service;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidProductUpdateException;
import com.example.demo.exception.ProductVersionConflictException;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductConcurrencyServiceTest {
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private ProductService productService;

    private ProductUpdateRequest request(Long version) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setName("orange");
        request.setPrice(200);
        request.setStock(20);
        request.setVersion(version);
        return request;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1})
    void missingOrNegativeVersionDoesNotReadOrWriteProduct(Long version) {
        assertThrows(InvalidProductUpdateException.class, () -> productService.update(1L, request(version)));
        verifyNoInteractions(productRepository);
    }

    @Test
    void nullUpdateRequestDoesNotReadOrWriteProduct() {
        assertThrows(InvalidProductUpdateException.class, () -> productService.update(1L, null));
        verifyNoInteractions(productRepository);
    }

    @Test
    void staleVersionDoesNotMutateProduct() {
        Product product = new Product(1L, "apple", 100, 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(ProductVersionConflictException.class, () -> productService.update(1L, request(1L)));

        assertEquals("apple", product.getName());
        assertEquals(100, product.getPrice());
        assertEquals(10, product.getStock());
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void matchingVersionUpdatesAndFlushesInsideServiceTransaction() {
        Product product = new Product(1L, "apple", 100, 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        var response = productService.update(1L, request(0L));

        assertEquals("orange", response.getName());
        assertEquals(200, response.getPrice());
        assertEquals(20, response.getStock());
        assertEquals(0L, response.getVersion());
        verify(productRepository).saveAndFlush(product);
    }
}
