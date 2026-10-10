package com.example.demo.service;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.entity.Product;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock private ProductRepository repository;
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
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> service.delete(999L));

        verify(repository).findById(999L);
        verifyNoMoreInteractions(repository);
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
        when(repository.findById(1L)).thenReturn(Optional.of(product));

        service.delete(1L);

        var calls = inOrder(repository);
        calls.verify(repository).findById(1L);
        calls.verify(repository).delete(product);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void repositoryLookupFailureIsNeverReclassifiedAsNotFound(String method) {
        var failure = new DataIntegrityViolationException("unexpected repository failure");
        when(repository.findById(1L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            switch (method) {
                case "GET" -> service.findById(1L);
                case "PUT" -> service.update(1L, request(0));
                default -> service.delete(1L);
            }
        });

        assertSame(failure, thrown);
        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void repositoryDeleteFailureIsNeverReclassifiedAsNotFound() {
        Product product = new Product(1L, "apple", 100L, 10);
        when(repository.findById(1L)).thenReturn(Optional.of(product));
        var failure = new DataIntegrityViolationException("unexpected delete failure");
        doThrow(failure).when(repository).delete(product);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.delete(1L)));

        verify(repository).findById(1L);
        verify(repository).delete(product);
        verifyNoMoreInteractions(repository);
    }

    private ProductUpdateRequest request(long version) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setName("orange");
        request.setPrice(200L);
        request.setVersion(version);
        return request;
    }
}
