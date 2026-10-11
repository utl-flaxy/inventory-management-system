package com.example.demo.service;

import com.example.demo.exception.InvalidOrderPageRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OrderPageableNormalizer {
    private static final Set<String> ALLOWED_SORTS = Set.of("id", "orderedAt", "status", "totalPrice");
    private static final Sort ID_DESC = Sort.by(Sort.Direction.DESC, "id");

    private OrderPageableNormalizer() { }

    // Springの共通resolverによる不正値のdefault化を避け、この2つのAPIだけを厳密に検証する。
    public static Pageable parse(MultiValueMap<String, String> parameters) {
        int page = number(parameters, "page", 0, 0);
        int size = number(parameters, "size", 20, 1);
        List<Sort.Order> orders = new ArrayList<>();
        for (String value : parameters.getOrDefault("sort", List.of())) {
            String[] parts = value.split(",", -1);
            if (parts.length != 2 || !ALLOWED_SORTS.contains(parts[0])
                    || !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) {
                throw new InvalidOrderPageRequestException();
            }
            orders.add(new Sort.Order(Sort.Direction.fromString(parts[1]), parts[0]));
        }
        return normalize(PageRequest.of(page, Math.min(size, 100), Sort.by(orders)));
    }

    public static Pageable normalize(Pageable pageable) {
        if (pageable == null) throw new InvalidOrderPageRequestException();
        var properties = new HashSet<String>();
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORTS.contains(order.getProperty()) || !properties.add(order.getProperty())
                    || order.isIgnoreCase() || order.getNullHandling() != Sort.NullHandling.NATIVE) {
                throw new InvalidOrderPageRequestException();
            }
        }
        if (!properties.contains("id")) sort = sort.and(ID_DESC);
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), 100) : 20;
        // JPAのoffsetはint。巨大なpageも500/offset overflowへ落とさず400にする。
        if ((long) page * size > Integer.MAX_VALUE) throw new InvalidOrderPageRequestException();
        return PageRequest.of(page, size, sort);
    }

    private static int number(MultiValueMap<String, String> parameters, String name, int fallback, int minimum) {
        List<String> values = parameters.get(name);
        if (values == null) return fallback;
        if (values.size() != 1 || !values.get(0).matches("[0-9]+")) throw new InvalidOrderPageRequestException();
        try {
            int value = Integer.parseInt(values.get(0));
            if (value < minimum) throw new InvalidOrderPageRequestException();
            return value;
        } catch (NumberFormatException ex) {
            throw new InvalidOrderPageRequestException();
        }
    }
}
