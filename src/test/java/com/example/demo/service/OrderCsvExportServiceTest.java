package com.example.demo.service;

import com.example.demo.config.OrderCsvExportProperties;
import com.example.demo.exception.OrderCsvExportBusyException;
import com.example.demo.exception.OrderCsvExportLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.SQLTimeoutException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderCsvExportServiceTest {
    private static final int LIMIT = 64 * 1024;
    OrderService orders;
    OrderCsvExportService exports;

    @BeforeEach void setup() {
        orders = mock(OrderService.class);
        var properties = new OrderCsvExportProperties();
        properties.setMaxBytes(LIMIT);
        exports = new OrderCsvExportService(orders, properties);
    }

    private static long spoolFiles() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("order-csv-")).count();
        }
    }

    private void writes(int bytes) throws IOException {
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(0);
            output.write(new byte[bytes - 1]);
            output.write('x'); // single-byte path is bounded too
            return null;
        }).when(orders).exportCsv(any());
    }

    @Test void defaultsFitTheProductionTmpfsAndInvalidBoundsFailFast() {
        var defaults = new OrderCsvExportProperties();
        assertEquals(16L * 1024 * 1024, defaults.getMaxBytes());
        assertEquals(1, defaults.getMaxConcurrent());
        assertDoesNotThrow(defaults::validate);
        // Compose mounts /tmp as a 32 MiB tmpfs; the worst-case concurrent spool total stays below it.
        assertTrue(OrderCsvExportProperties.MAX_TOTAL_SPOOL_BYTES < 32L * 1024 * 1024);
        for (long[] invalid : new long[][]{{64 * 1024 - 1, 1}, {LIMIT, 0}, {LIMIT, 5}, {16L * 1024 * 1024, 2}}) {
            var properties = new OrderCsvExportProperties();
            properties.setMaxBytes(invalid[0]);
            properties.setMaxConcurrent((int) invalid[1]);
            assertThrows(IllegalArgumentException.class, () -> new OrderCsvExportService(orders, properties));
        }
    }

    @Test void prepareIsNeverJoinedToAnOuterTransaction() throws Exception {
        var transactional = OrderCsvExportService.class.getMethod("prepare").getAnnotation(Transactional.class);
        assertEquals(Propagation.NEVER, transactional.propagation());
    }

    @Test void spoolOfExactlyTheLimitIsSentCompletelyThenDeletedAndPermitReleased() throws Exception {
        writes(LIMIT);
        long before = spoolFiles();
        var output = new ByteArrayOutputStream();
        try (var spool = exports.prepare()) {
            assertEquals(LIMIT, spool.size());
            assertEquals(before + 1, spoolFiles());
            spool.transferTo(output);
        }
        assertEquals(LIMIT, output.size());
        assertEquals(before, spoolFiles());
        try (var next = exports.prepare()) { assertEquals(LIMIT, next.size()); }
    }

    @Test void spoolFileIsOwnerOnlyWherePosixPermissionsExist() throws Exception {
        writes(1);
        try (var spool = exports.prepare()) {
            try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                var file = files.filter(path -> path.getFileName().toString().startsWith("order-csv-")).findFirst().orElseThrow();
                if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
                    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
            }
            assertEquals(1, spool.size());
        }
    }

    @Test void oneByteOverTheLimitIsRejectedAsSizeLimitAndCleanedUp() throws Exception {
        writes(LIMIT + 1);
        long before = spoolFiles();
        var failure = assertThrows(OrderCsvExportLimitExceededException.class, exports::prepare);
        assertEquals(OrderCsvExportLimitExceededException.Reason.SIZE, failure.reason());
        assertNull(failure.getCause());
        assertEquals(before, spoolFiles());
        writes(1);
        try (var next = exports.prepare()) { assertEquals(1, next.size()); }
    }

    @ParameterizedTest @CsvSource({"transaction", "query", "sql"})
    void deadlineFailuresAreTimeLimitWithoutRawCause(String kind) throws Exception {
        RuntimeException failure = switch (kind) {
            case "transaction" -> new TransactionTimedOutException("private deadline");
            case "query" -> new QueryTimeoutException("private query");
            default -> new DataAccessResourceFailureException("wrapped", new SQLTimeoutException("private sql"));
        };
        doThrow(failure).when(orders).exportCsv(any());
        var rejected = assertThrows(OrderCsvExportLimitExceededException.class, exports::prepare);
        assertEquals(OrderCsvExportLimitExceededException.Reason.TIME, rejected.reason());
        assertNull(rejected.getCause());
        assertFalse(rejected.getMessage().contains("private"));
    }

    @Test void otherFailuresKeepTheirTypeForTheExistingSafe500ContractAndReleaseThePermit() throws Exception {
        var failure = new DataAccessResourceFailureException("db down");
        doThrow(failure).when(orders).exportCsv(any());
        long before = spoolFiles();
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, exports::prepare));
        var io = new IOException("disk");
        doThrow(io).when(orders).exportCsv(any());
        assertSame(io, assertThrows(IOException.class, exports::prepare));
        assertEquals(before, spoolFiles());
        writes(1);
        try (var next = exports.prepare()) { assertEquals(1, next.size()); }
    }

    @Test void permitIsHeldUntilCloseAndDoubleCloseDoesNotCreateExtraCapacity() throws Exception {
        writes(1);
        var first = exports.prepare();
        assertThrows(OrderCsvExportBusyException.class, exports::prepare);
        verify(orders, times(1)).exportCsv(any()); // rejected before querying
        first.close();
        first.close();
        var second = exports.prepare();
        assertThrows(OrderCsvExportBusyException.class, exports::prepare);
        second.close();
    }

    @Test void transferFailureStillDeletesSpoolAndReleasesPermitOnClose() throws Exception {
        writes(LIMIT);
        long before = spoolFiles();
        try (var spool = exports.prepare()) {
            assertThrows(IOException.class, () -> spool.transferTo(new OutputStream() {
                @Override public void write(int b) throws IOException { throw new IOException("client gone"); }
            }));
        }
        assertEquals(before, spoolFiles());
        try (var next = exports.prepare()) { assertEquals(LIMIT, next.size()); }
    }
}
