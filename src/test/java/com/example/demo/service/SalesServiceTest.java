package com.example.demo.service;

import com.example.demo.dto.SalesResponse;
import com.example.demo.exception.InvalidOrderRequestException;
import com.example.demo.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {
    @Mock private OrderRepository orders;
    @InjectMocks private OrderService service;

    @ParameterizedTest
    @CsvSource({"null,null", "2026-10-01T00:00:00Z,null", "null,2026-11-01T00:00:00Z",
            "2026-10-01T00:00:00Z,2026-11-01T00:00:00Z"})
    void optionalBoundsUseOneAggregateQueryWithoutLoadingOrders(String lower, String upper) {
        Instant from = bound(lower), to = bound(upper);
        SalesResponse totals = new SalesResponse(7_000_000_000L, 2L);
        when(orders.aggregateSales(from, to)).thenReturn(totals);
        assertSame(totals, service.getSales(from, to));
        verify(orders).aggregateSales(from, to);
        verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource({"2026-11-01T00:00:00Z,2026-10-01T00:00:00Z",
            "2026-10-01T00:00:00Z,2026-10-01T00:00:00Z"})
    void emptyOrReversedRangeIsRejectedBeforeRepository(String from, String to) {
        assertThrows(InvalidOrderRequestException.class, () -> service.getSales(bound(from), bound(to)));
        verifyNoInteractions(orders);
    }

    @Test
    void noArgumentEntryPointRetainsAllTimeQuery() {
        SalesResponse totals = new SalesResponse(0L, 0L);
        when(orders.aggregateSales(null, null)).thenReturn(totals);
        assertSame(totals, service.getSales());
        verify(orders).aggregateSales(null, null);
        verifyNoMoreInteractions(orders);
    }

    private Instant bound(String value) {
        return "null".equals(value) ? null : Instant.parse(value);
    }
}
