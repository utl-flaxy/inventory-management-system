package com.example.demo.service;

import com.example.demo.dto.ProductResponse;
import com.example.demo.entity.Product;
import com.example.demo.exception.InvalidProductPageRequestException;
import com.example.demo.exception.InvalidProductSearchKeywordException;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductPagingServiceTest {
    @Mock ProductRepository products;
    @Mock OrderItemRepository items;
    @Mock InventoryMovementRepository movements;
    @InjectMocks ProductService service;

    static Stream<Arguments> sorts() {
        return Stream.of(false, true).flatMap(search -> Stream.of(
                Arguments.of(search, Sort.unsorted(), Sort.by("id")),
                Arguments.of(search, Sort.by("price"), Sort.by("price", "id")),
                Arguments.of(search, Sort.by(Sort.Direction.DESC, "name"),
                        Sort.by(Sort.Order.desc("name"), Sort.Order.asc("id"))),
                Arguments.of(search, Sort.by("stock"), Sort.by("stock", "id")),
                Arguments.of(search, Sort.by("version"), Sort.by("version", "id")),
                Arguments.of(search, Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id")),
                        Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id"))),
                Arguments.of(search, Sort.by(Sort.Order.asc("price"), Sort.Order.desc("name")),
                        Sort.by(Sort.Order.asc("price"), Sort.Order.desc("name"), Sort.Order.asc("id")))));
    }

    @ParameterizedTest
    @MethodSource("sorts")
    void bothQueriesUseNormalizedSort(boolean search, Sort requested, Sort expected) {
        Pageable normalized = PageRequest.of(2, 7, expected);
        if (search) when(products.findByNameContaining("apple", normalized)).thenReturn(Page.empty(normalized));
        else when(products.findAll(normalized)).thenReturn(Page.empty(normalized));

        var result = query(search, PageRequest.of(2, 7, requested));

        assertEquals(normalized, result.getPageable());
        if (search) verify(products).findByNameContaining("apple", normalized);
        else verify(products).findAll(normalized);
        verifyNoMoreInteractions(products);
        verifyNoInteractions(items, movements);
    }

    static Stream<Arguments> unknownSorts() {
        return Stream.of(false, true).flatMap(search -> Stream.of("createdAt", "foo", "password", "orders", "class")
                .map(property -> Arguments.of(search, property)));
    }

    @ParameterizedTest
    @MethodSource("unknownSorts")
    void rejectsUnknownSortBeforeAnyRepositoryAccess(boolean search, String property) {
        assertThrows(InvalidProductPageRequestException.class,
                () -> query(search, PageRequest.of(0, 20, Sort.by("price", property))));
        verifyNoInteractions(products, items, movements);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directUnpagedCallsStillHaveBoundedDefault(boolean search) {
        var normalized = PageRequest.of(0, 20, Sort.by("id"));
        if (search) when(products.findByNameContaining("apple", normalized)).thenReturn(Page.empty(normalized));
        else when(products.findAll(normalized)).thenReturn(Page.empty(normalized));
        assertEquals(normalized, query(search, Pageable.unpaged()).getPageable());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directCallsCannotBypassMaximumSize(boolean search) {
        var normalized = PageRequest.of(0, 100, Sort.by("id"));
        if (search) when(products.findByNameContaining("apple", normalized)).thenReturn(Page.empty(normalized));
        else when(products.findAll(normalized)).thenReturn(Page.empty(normalized));
        assertEquals(normalized, query(search, PageRequest.of(0, 500)).getPageable());
    }

    @Test
    void searchPassesKeywordUnchangedAndKeepsAllProductFields() {
        var pageable = PageRequest.of(0, 20, Sort.by("id"));
        var product = new Product(1L, " Apple ", 150L, 10);
        when(products.findByNameContaining(" Apple ", pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 1));

        assertEquals(List.of(ProductResponse.builder().id(1L).name(" Apple ").price(150L)
                .stock(10).version(product.getVersion()).build()),
                service.search(" Apple ", PageRequest.of(0, 20)).getContent());
        verify(products).findByNameContaining(" Apple ", pageable);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\r\n", "\u3000", "\u00a0", "\u2003"})
    void searchRejectsBlankUnicodeAndNullBeforeRepository(String keyword) {
        assertThrows(InvalidProductSearchKeywordException.class,
                () -> service.search(keyword, PageRequest.of(0, 20)));
        verifyNoInteractions(products, items, movements);
    }

    @Test
    void searchUsesCodePointsRatherThanUtf16UnitsAndKeepsOriginalKeyword() {
        var keyword = "🍎".repeat(100);
        var normalized = PageRequest.of(0, 20, Sort.by("id"));
        when(products.findByNameContaining(keyword, normalized)).thenReturn(Page.empty(normalized));

        assertTrue(service.search(keyword, PageRequest.of(0, 20)).isEmpty());
        verify(products).findByNameContaining(keyword, normalized);
        verifyNoInteractions(items, movements);
    }

    @Test
    void searchRejectsOverlongUnicodeBeforeRepository() {
        assertThrows(InvalidProductSearchKeywordException.class,
                () -> service.search("🍎".repeat(101), PageRequest.of(0, 20)));
        verifyNoInteractions(products, items, movements);
    }

    @Test
    void searchPreservesSignificantSpacesAndSpecialCharacters() {
        var keyword = " %_Apple\\ ";
        var normalized = PageRequest.of(0, 20, Sort.by("id"));
        when(products.findByNameContaining(keyword, normalized)).thenReturn(Page.empty(normalized));
        assertTrue(service.search(keyword, PageRequest.of(0, 20)).isEmpty());
        verify(products).findByNameContaining(keyword, normalized);
    }

    private Page<ProductResponse> query(boolean search, Pageable pageable) {
        return search ? service.search("apple", pageable) : service.findAll(pageable);
    }
}
