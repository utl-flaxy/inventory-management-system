package com.example.demo.service;

import com.example.demo.dto.OrderCsvRow;
import com.example.demo.entity.OrderStatus;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static com.example.demo.CsvAssertions.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCsvServiceTest {
    @Mock OrderItemRepository items;
    @Mock OrderRepository orders;
    @InjectMocks OrderService service;

    private List<OrderCsvRow> batch(long from, int size) {
        return LongStream.range(from, from + size).mapToObj(id ->
                new OrderCsvRow(1L, id, "item" + id, 1, 100L, OrderStatus.PENDING.name())).toList();
    }

    @Test
    void emptyExportIsUtf8HeaderOnly() throws Exception {
        when(items.findCsvBatch(eq(0L), eq(0L), anyInt())).thenReturn(List.of());
        var output = new ByteArrayOutputStream();
        service.exportCsv(output);
        assertEquals(1, parse(output.toString(StandardCharsets.UTF_8)).size());
        verifyNoInteractions(orders);
    }

    @Test
    void largeExportFlushesEveryBoundedBatchBeforeReadingNextAndDoesNotCloseServletStream() throws Exception {
        int batches = 100;
        AtomicInteger calls = new AtomicInteger();
        class CountingStream extends OutputStream {
            long bytes; int flushes; boolean closed;
            public void write(int b) { bytes++; }
            public void write(byte[] b, int off, int len) { bytes += len; }
            public void flush() { flushes++; }
            public void close() { closed = true; }
        }
        var output = new CountingStream();
        when(items.findCsvBatch(anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
            int call = calls.getAndIncrement();
            assertEquals(500, (int) invocation.getArgument(2));
            assertEquals(call * 500L, (long) invocation.getArgument(1));
            assertEquals(call == 0 ? 0L : 1L, (long) invocation.getArgument(0));
            assertTrue(output.flushes >= call);
            return call == batches ? List.of() : batch(call * 500L + 1, 500);
        });
        service.exportCsv(output);
        assertEquals(101, calls.get()); // 50,000 rows, constant 500-row allocation per read.
        assertTrue(output.bytes > 1_000_000);
        assertFalse(output.closed);
        verifyNoInteractions(orders);
        verify(items, never()).save(any());
    }

    @Test
    void multipleBatchesHaveNoMissingOrRepeatedRows() throws Exception {
        when(items.findCsvBatch(eq(0L), eq(0L), anyInt())).thenReturn(batch(1, 500));
        when(items.findCsvBatch(eq(1L), eq(500L), anyInt())).thenReturn(batch(501, 500));
        when(items.findCsvBatch(eq(1L), eq(1000L), anyInt())).thenReturn(batch(1001, 1));
        var output = new ByteArrayOutputStream();
        service.exportCsv(output);
        var rows = parse(output.toString(StandardCharsets.UTF_8));
        assertEquals(1002, rows.size());
        for (int id = 1; id <= 1001; id++) assertEquals("item" + id, rows.get(id).get(1));
    }

    @Test
    void clientDisconnectPropagatesAndStopsReadingFurtherBatches() {
        when(items.findCsvBatch(eq(0L), eq(0L), anyInt())).thenReturn(batch(1, 500));
        var failure = new IOException("simulated disconnect");
        var output = new OutputStream() { public void write(int b) throws IOException { throw failure; } };
        assertSame(failure, assertThrows(IOException.class, () -> service.exportCsv(output)));
        verify(items, times(1)).findCsvBatch(anyLong(), anyLong(), anyInt());
    }

    @Test
    void databaseFailureAfterFirstBatchIsNotSwallowed() {
        when(items.findCsvBatch(eq(0L), eq(0L), anyInt())).thenReturn(batch(1, 500));
        var failure = new IllegalStateException("simulated database failure");
        when(items.findCsvBatch(eq(1L), eq(500L), anyInt())).thenThrow(failure);
        var output = new ByteArrayOutputStream();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.exportCsv(output)));
        assertTrue(output.size() > 0);
    }
}
