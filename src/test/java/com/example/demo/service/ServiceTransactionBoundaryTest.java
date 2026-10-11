package com.example.demo.service;

import com.example.demo.dto.OrderRequest;
import com.example.demo.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServiceTransactionBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"createOrder", "findMyOrder", "cancelMyOrder", "findById",
            "updateStatus", "shipOrder", "completeOrder", "cancelOrder"})
    void httpDtoBoundaryKeepsTheBusinessTransactionAttributes(String method) throws Exception {
        Class<?>[] parameters = switch (method) {
            case "createOrder" -> new Class<?>[]{OrderRequest.class, String.class};
            case "updateStatus" -> new Class<?>[]{Long.class, OrderStatus.class};
            default -> new Class<?>[]{Long.class};
        };
        var business = OrderService.class.getMethod(method, parameters).getAnnotation(Transactional.class);
        var http = OrderService.class.getMethod(method + "Response", parameters).getAnnotation(Transactional.class);
        assertNotNull(business);
        assertEquals(business, http, "Self-invocation must preserve isolation, readOnly and rollback rules");
    }

    @ParameterizedTest
    @ValueSource(strings = {"findAll", "search", "findById"})
    void productReadsAreReadOnlyWithoutChangingDefaultIsolation(String method) throws Exception {
        Class<?>[] parameters = switch (method) {
            case "findAll" -> new Class<?>[]{Pageable.class};
            case "search" -> new Class<?>[]{String.class, Pageable.class};
            default -> new Class<?>[]{Long.class};
        };
        var tx = ProductService.class.getMethod(method, parameters).getAnnotation(Transactional.class);
        assertTrue(tx.readOnly());
        assertEquals(Isolation.DEFAULT, tx.isolation());
        assertNull(ProductService.class.getAnnotation(Transactional.class), "Do not make writes read-only by default");
    }

    @ParameterizedTest
    @ValueSource(strings = {"findAll", "findMyOrders"})
    void orderPagesKeepTheirReadOnlyRepeatableReadSnapshot(String method) throws Exception {
        var tx = OrderService.class.getMethod(method, Pageable.class).getAnnotation(Transactional.class);
        assertTrue(tx.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
    }

    @Test
    void csvKeepsTheSynchronousReadOnlySnapshotAndIoRollback() throws Exception {
        var tx = OrderService.class.getMethod("exportCsv", OutputStream.class).getAnnotation(Transactional.class);
        assertTrue(tx.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
        assertArrayEquals(new Class<?>[]{IOException.class}, tx.rollbackFor());
    }
}
