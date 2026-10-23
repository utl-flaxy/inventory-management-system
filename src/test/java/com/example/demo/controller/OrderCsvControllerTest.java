package com.example.demo.controller;

import com.example.demo.config.OrderCsvExportProperties;
import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.exception.OrderCsvExportException;
import com.example.demo.service.OrderCsvExportService;
import com.example.demo.service.OrderCsvWriter;
import com.example.demo.service.OrderService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionTimedOutException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import static com.example.demo.CsvAssertions.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderCsvControllerTest {
    OrderService service;
    OrderCsvExportService exports;
    AdminOrderController controller;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(OrderService.class);
        var properties = new OrderCsvExportProperties();
        properties.setMaxBytes(64 * 1024);
        exports = new OrderCsvExportService(service, properties);
        controller = new AdminOrderController(service, exports);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private void writesHeaderOnly() throws IOException {
        doAnswer(invocation -> {
            var writer = new OutputStreamWriter((OutputStream) invocation.getArgument(0), StandardCharsets.UTF_8);
            OrderCsvWriter.header(writer); writer.flush(); return null;
        }).when(service).exportCsv(any());
    }

    @Test void sendsSpooledUtf8CsvWithExistingFilenameHeaderAndContentLength() throws Exception {
        writesHeaderOnly();
        var response = mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=orders.csv"))
                .andExpect(content().contentType("text/csv;charset=UTF-8")).andReturn().getResponse();
        byte[] body = response.getContentAsByteArray();
        assertEquals(body.length, response.getContentLengthLong());
        assertEquals(1, parse(new String(body, StandardCharsets.UTF_8)).size());
        verify(service).exportCsv(any());
    }

    @Test void failureBeforeCommitKeepsExistingSafeProblemDetailContract() throws Exception {
        doThrow(new IllegalStateException("private SQL value")).when(service).exportCsv(any());
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(header().doesNotExist("Content-Disposition"));
        writesHeaderOnly(); // The permit and spool were released: the next export succeeds.
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isOk());
    }

    @Test void transactionDeadlineAndSizeLimitAreProblemDetailsBeforeAnyCsvHeader() throws Exception {
        doThrow(new TransactionTimedOutException("deadline")).when(service).exportCsv(any());
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_CSV_EXPORT_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Content-Disposition"));
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(new byte[64 * 1024 + 1]); return null;
        }).when(service).exportCsv(any());
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_CSV_EXPORT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deadline"))))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test void concurrentExportIsRejectedBeforeQueryingWhileASpoolIsOpen() throws Exception {
        writesHeaderOnly();
        try (var held = exports.prepare()) {
            mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ORDER_CSV_EXPORT_BUSY"))
                    .andExpect(header().doesNotExist("Content-Disposition"));
            verify(service, times(1)).exportCsv(any());
            assertTrue(held.size() > 0);
        }
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isOk());
    }

    @Test void transferFailureAfterCommitAbortsWithoutExposingCauseAndReleasesSpool() throws Exception {
        writesHeaderOnly();
        var response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int b) throws IOException { throw new IOException("private client socket detail"); }
        });
        var failure = assertThrows(OrderCsvExportException.class, () -> controller.exportCsv(response));
        assertNull(failure.getCause());
        assertEquals("CSV export interrupted", failure.getMessage());
        verify(response).flushBuffer(); // headers were committed before the transfer, so no ProblemDetail is appended.
        try (var next = exports.prepare()) { assertTrue(next.size() > 0); }
    }
}
