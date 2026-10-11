package com.example.demo.service;

import com.example.demo.exception.InvalidOrderPageRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderPageableNormalizerTest {
    @Test
    void defaultsAndUnpagedServiceInputsAreBounded() {
        var expected = PageRequest.of(0, 20, Sort.Direction.DESC, "id");
        assertEquals(expected, OrderPageableNormalizer.parse(new LinkedMultiValueMap<>()));
        assertEquals(expected, OrderPageableNormalizer.normalize(Pageable.unpaged()));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "20,20", "100,100", "101,100", "500,100", "2147483647,100"})
    void sizesClampOnlyAbove100(String requested, int expected) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("page", "2");
        params.add("size", requested);
        var page = OrderPageableNormalizer.parse(params);
        assertEquals(2, page.getPageNumber());
        assertEquals(expected, page.getPageSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "abc", "1.5", " 1", "+1", "2147483648"})
    void invalidPageIsRejected(String value) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("page", value);
        assertThrows(InvalidOrderPageRequestException.class, () -> OrderPageableNormalizer.parse(params));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "abc", "1.5", " 1", "+1", "2147483648"})
    void invalidSizeIsRejected(String value) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("size", value);
        assertThrows(InvalidOrderPageRequestException.class, () -> OrderPageableNormalizer.parse(params));
    }

    @ParameterizedTest
    @CsvSource({"id,asc", "id,desc", "orderedAt,asc", "orderedAt,desc", "status,asc", "status,desc",
            "totalPrice,asc", "totalPrice,desc"})
    void allowedSortsPreserveDirectionAndAppendIdExactlyOnce(String property, String direction) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("sort", property + "," + direction);
        var orders = OrderPageableNormalizer.parse(params).getSort().stream().toList();
        assertEquals(Sort.Direction.fromString(direction), orders.get(0).getDirection());
        assertEquals(property, orders.get(0).getProperty());
        assertEquals(property.equals("id") ? 1 : 2, orders.size());
        assertEquals(1, orders.stream().filter(order -> order.getProperty().equals("id")).count());
        if (!property.equals("id")) assertEquals(Sort.Order.desc("id"), orders.get(1));
    }

    @Test
    void compoundSortRespectsExplicitIdInsteadOfDuplicatingIt() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("sort", "status,asc");
        params.add("sort", "totalPrice,desc");
        params.add("sort", "id,asc");
        assertEquals(List.of(Sort.Order.asc("status"), Sort.Order.desc("totalPrice"), Sort.Order.asc("id")),
                OrderPageableNormalizer.parse(params).getSort().stream().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "id", "id,", ",asc", "id,sideways", "id,asc,desc", "customer.password,asc",
            "items,desc", "total_price,asc", "id;DROP TABLE orders,desc"})
    void malformedOrUnknownSortIsRejected(String sort) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("sort", sort);
        assertThrows(InvalidOrderPageRequestException.class, () -> OrderPageableNormalizer.parse(params));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "sort"})
    void repeatedScalarParametersAndDuplicateSortPropertiesAreRejected(String parameter) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add(parameter, parameter.equals("sort") ? "id,asc" : "1");
        params.add(parameter, parameter.equals("sort") ? "id,desc" : "1");
        assertThrows(InvalidOrderPageRequestException.class, () -> OrderPageableNormalizer.parse(params));
    }

    @Test
    void jpaOffsetOverflowAndNonNativeServiceSortOptionsAreRejected() {
        assertThrows(InvalidOrderPageRequestException.class,
                () -> OrderPageableNormalizer.normalize(PageRequest.of(Integer.MAX_VALUE, 20)));
        assertThrows(InvalidOrderPageRequestException.class,
                () -> OrderPageableNormalizer.normalize(PageRequest.of(0, 20, Sort.by(Sort.Order.asc("status").ignoreCase()))));
        assertThrows(InvalidOrderPageRequestException.class,
                () -> OrderPageableNormalizer.normalize(PageRequest.of(0, 20, Sort.by(Sort.Order.asc("status").nullsLast()))));
        assertThrows(InvalidOrderPageRequestException.class, () -> OrderPageableNormalizer.normalize(null));
    }
}
