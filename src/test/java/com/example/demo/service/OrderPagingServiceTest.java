package com.example.demo.service;

import com.example.demo.dto.OrderPageRow;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.User;
import com.example.demo.exception.InvalidOrderPageRequestException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPagingServiceTest {
    @Mock OrderRepository orders;
    @Mock ProductRepository products;
    @Mock CurrentUser currentUser;
    @InjectMocks OrderService service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundedQueriesRestoreIdPageOrderAndPreserveSnapshotDto(boolean self) {
        var pageable = PageRequest.of(1, 100, Sort.Direction.ASC, "totalPrice");
        var normalized = PageRequest.of(1, 100, Sort.by("totalPrice").and(Sort.by(Sort.Direction.DESC, "id")));
        var ids = new PageImpl<>(List.of(2L, 1L), normalized, 202);
        var rows = List.of(row(1, 11, "保存名", 3_000_000_000L, 2), row(1, 12, "second", 100, 1),
                row(2, 21, "another", 50, 1));
        if (self) {
            when(currentUser.requireUser()).thenReturn(new User(7L, "owner", "unused", "ROLE_USER"));
            when(orders.findOrderIdsByCustomerId(7L, normalized)).thenReturn(ids);
            when(orders.findPageRowsByCustomerId(List.of(2L, 1L), 7L)).thenReturn(rows);
        } else {
            when(orders.findOrderIds(normalized)).thenReturn(ids);
            when(orders.findPageRows(List.of(2L, 1L))).thenReturn(rows);
        }
        var result = self ? service.findMyOrders(pageable) : service.findAll(pageable);
        assertEquals(List.of(2L, 1L), result.map(response -> response.id()).getContent());
        assertEquals(202, result.getTotalElements());
        assertEquals(3, result.getTotalPages());
        assertEquals(1, result.getNumber());
        assertEquals(100, result.getSize());
        var response = result.getContent().get(1);
        assertEquals("owner", response.customer().username());
        assertEquals(7, response.customer().id());
        assertEquals(OrderStatus.COMPLETED, response.status());
        assertEquals(Instant.EPOCH, response.completedAt());
        assertEquals(4, response.version());
        assertEquals("保存名", response.items().get(0).productName());
        assertEquals(3_000_000_000L, response.items().get(0).unitPrice());
        assertEquals(6_000_000_000L, response.items().get(0).lineTotal());
        assertEquals(2, response.items().size());
        verifyNoInteractions(products);
        if (self) {
            verify(orders).findOrderIdsByCustomerId(7L, normalized);
            verify(orders).findPageRowsByCustomerId(List.of(2L, 1L), 7L);
        } else {
            verify(orders).findOrderIds(normalized);
            verify(orders).findPageRows(List.of(2L, 1L));
        }
        verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyAndOutOfRangePagesNeverQueryDetails(boolean self) {
        var pageable = PageRequest.of(4, 20, Sort.Direction.DESC, "id");
        if (self) {
            when(currentUser.requireUser()).thenReturn(new User(7L, "owner", "unused", "ROLE_USER"));
            when(orders.findOrderIdsByCustomerId(7L, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 3));
        } else {
            when(orders.findOrderIds(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 3));
        }
        Page<?> response = self ? service.findMyOrders(pageable) : service.findAll(pageable);
        assertTrue(response.isEmpty());
        assertEquals(3, response.getTotalElements());
        assertEquals(4, response.getNumber());
        if (self) verify(orders).findOrderIdsByCustomerId(7L, pageable);
        else verify(orders).findOrderIds(pageable);
        verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serviceCapsUntrustedPageSizeBeforeRepository(boolean self) {
        var capped = PageRequest.of(0, 100, Sort.Direction.DESC, "id");
        if (self) {
            when(currentUser.requireUser()).thenReturn(new User(7L, "owner", "unused", "ROLE_USER"));
            when(orders.findOrderIdsByCustomerId(7L, capped)).thenReturn(Page.empty(capped));
        } else when(orders.findOrderIds(capped)).thenReturn(Page.empty(capped));
        var requested = PageRequest.of(0, 500);
        assertEquals(100, (self ? service.findMyOrders(requested) : service.findAll(requested)).getSize());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsafeSortIsRejectedBeforeAnyDatabaseAccess(boolean self) {
        var pageable = PageRequest.of(0, 20, Sort.by("customer.password"));
        assertThrows(InvalidOrderPageRequestException.class,
                () -> { if (self) service.findMyOrders(pageable); else service.findAll(pageable); });
        verifyNoInteractions(orders, currentUser, products);
    }

    @Test
    void legacyOrderWithoutItemsStillHasEmptyItemsArray() {
        var pageable = PageRequest.of(0, 20, Sort.Direction.DESC, "id");
        when(orders.findOrderIds(pageable)).thenReturn(new PageImpl<>(List.of(1L), pageable, 1));
        when(orders.findPageRows(List.of(1L))).thenReturn(List.of(new OrderPageRow(1L, OrderStatus.PENDING,
                Instant.EPOCH, Instant.EPOCH, null, 0L, 0L, 7L, "owner", null, null, null, null, null)));
        assertTrue(service.findAll(pageable).getContent().get(0).items().isEmpty());
    }

    private OrderPageRow row(long id, long itemId, String name, long price, int quantity) {
        return new OrderPageRow(id, OrderStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                id == 1 ? 6_000_000_100L : 50L, 4L, 7L, "owner", itemId, itemId, name, price, quantity);
    }
}
