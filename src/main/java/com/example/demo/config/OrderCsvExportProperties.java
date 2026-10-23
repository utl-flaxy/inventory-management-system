package com.example.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds for the ADMIN order CSV snapshot spool (Issue #156). The spool lives in java.io.tmpdir, which is the
 * Compose 32 MiB tmpfs (memory-backed) in production, so the worst case concurrent spool total is capped below it.
 */
@Component
@ConfigurationProperties("app.order-csv")
@Getter
@Setter
public class OrderCsvExportProperties {
    public static final long MAX_TOTAL_SPOOL_BYTES = 24L * 1024 * 1024;

    private long maxBytes = 16L * 1024 * 1024;
    private int maxConcurrent = 1;

    public void validate() {
        if (maxBytes < 64L * 1024 || maxConcurrent < 1 || maxConcurrent > 4
                || maxBytes * maxConcurrent > MAX_TOTAL_SPOOL_BYTES) {
            throw new IllegalArgumentException("Invalid order CSV export configuration");
        }
    }
}
