package com.example.demo.controller;

import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.exception.OrderCsvExportException;
import com.example.demo.service.OrderCsvWriter;
import com.example.demo.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(OrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void streamsUtf8CsvWithExistingFilenameAndHeader() throws Exception {
        doAnswer(invocation -> {
            var writer = new OutputStreamWriter((OutputStream) invocation.getArgument(0), StandardCharsets.UTF_8);
            OrderCsvWriter.header(writer); writer.flush(); return null;
        }).when(service).exportCsv(any());
        var response = mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=orders.csv"))
                .andExpect(content().contentType("text/csv;charset=UTF-8")).andReturn().getResponse();
        assertEquals(1, parse(response.getContentAsString(StandardCharsets.UTF_8)).size());
        verify(service).exportCsv(any());
    }

    @Test void failureBeforeCommitKeepsExistingSafeProblemDetailContract() throws Exception {
        doThrow(new IllegalStateException("private SQL value")).when(service).exportCsv(any());
        mvc.perform(get("/api/v1/admin/orders/export")).andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test void failureAfterCommitAbortsWithoutAppendingProblemJsonOrExposingCause() throws Exception {
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(0);
            output.write("partial csv\r\n".getBytes(StandardCharsets.UTF_8)); output.flush();
            throw new IOException("private SQL value");
        }).when(service).exportCsv(any());
        var failure = assertThrows(jakarta.servlet.ServletException.class,
                () -> mvc.perform(get("/api/v1/admin/orders/export")));
        assertInstanceOf(OrderCsvExportException.class, failure.getCause());
        assertNull(failure.getCause().getCause());
        assertEquals("CSV export interrupted", failure.getCause().getMessage());
    }
}
