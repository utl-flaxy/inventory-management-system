package com.example.demo.service;

import com.example.demo.config.OrderCsvExportProperties;
import com.example.demo.exception.OrderCsvExportBusyException;
import com.example.demo.exception.OrderCsvExportLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.Semaphore;

/**
 * Issue #156: separates the DB snapshot from the HTTP transfer. The existing read-only REPEATABLE_READ export
 * writes into a size-bounded temp file within the normal transaction deadline; only after commit/rollback is the
 * file sent, so a slow client never holds a DB connection, transaction or MVCC snapshot. A permit bounds
 * concurrent spools (DB load and tmpfs use) and is held until the transfer ends.
 */
@Service
@Slf4j
public class OrderCsvExportService {
    private final OrderService orders;
    private final long maxBytes;
    private final Semaphore permits;

    public OrderCsvExportService(OrderService orders, OrderCsvExportProperties properties) {
        properties.validate();
        this.orders = orders;
        this.maxBytes = properties.getMaxBytes();
        this.permits = new Semaphore(properties.getMaxConcurrent());
    }

    /** NEVER: an outer transaction would extend the snapshot across the HTTP transfer again. */
    @Transactional(propagation = Propagation.NEVER)
    public Spool prepare() throws IOException {
        if (!permits.tryAcquire()) throw new OrderCsvExportBusyException();
        Path file = null;
        try {
            file = Files.createTempFile("order-csv-", ".csv");
            try (var output = new BoundedOutputStream(Files.newOutputStream(file), maxBytes)) {
                orders.exportCsv(output);
            }
            return new Spool(file, Files.size(file));
        } catch (IOException | RuntimeException failure) {
            delete(file);
            permits.release();
            var reason = limitReason(failure);
            if (reason != null) throw new OrderCsvExportLimitExceededException(reason);
            throw failure; // Other DB/IO failures keep the existing safe 500 contract; no CSV byte was sent.
        }
    }

    private static OrderCsvExportLimitExceededException.Reason limitReason(Exception failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SpoolLimitExceededException) return OrderCsvExportLimitExceededException.Reason.SIZE;
            if (cause instanceof TransactionTimedOutException || cause instanceof QueryTimeoutException
                    || cause instanceof SQLTimeoutException) return OrderCsvExportLimitExceededException.Reason.TIME;
        }
        return null;
    }

    private static void delete(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException failure) {
            // The path is random but still not logged; tmpfs is cleared on container restart.
            log.atWarn().addKeyValue("event", "order.csv_export.spool_cleanup_failed")
                    .addKeyValue("exceptionType", failure.getClass().getName()).log("CSV spool cleanup failed");
        }
    }

    /** A committed, immutable CSV snapshot. Closing deletes the file and releases the permit exactly once. */
    public final class Spool implements AutoCloseable {
        private final Path file;
        private final long size;
        private boolean closed;

        private Spool(Path file, long size) {
            this.file = file;
            this.size = size;
        }

        public long size() { return size; }

        public void transferTo(OutputStream output) throws IOException {
            try (InputStream input = Files.newInputStream(file)) {
                input.transferTo(output);
            }
            output.flush();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            delete(file);
            permits.release();
        }
    }

    static final class SpoolLimitExceededException extends IOException {
        SpoolLimitExceededException() { super("CSV spool limit exceeded"); }
    }

    /** Rejects a write before any byte beyond the limit reaches the file. */
    static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        BoundedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override public void write(int b) throws IOException {
            reserve(1);
            out.write(b);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length > limit - written) throw new SpoolLimitExceededException();
            written += length;
        }
    }
}
