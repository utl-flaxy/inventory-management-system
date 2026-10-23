package com.example.demo.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PR #160 review: the spool budget check must fail closed without long-multiplication overflow. */
class OrderCsvExportPropertiesTest {
    private static final long BUDGET = OrderCsvExportProperties.MAX_TOTAL_SPOOL_BYTES; // 24 MiB
    private static final long MIN_BYTES = 64L * 1024;

    private static OrderCsvExportProperties properties(long maxBytes, int maxConcurrent) {
        var properties = new OrderCsvExportProperties();
        properties.setMaxBytes(maxBytes);
        properties.setMaxConcurrent(maxConcurrent);
        return properties;
    }

    @Test void defaultsAreSixteenMebibytesTimesOneAndValid() {
        var defaults = new OrderCsvExportProperties();
        assertEquals(16L * 1024 * 1024, defaults.getMaxBytes());
        assertEquals(1, defaults.getMaxConcurrent());
        assertEquals(24L * 1024 * 1024, BUDGET);
        assertDoesNotThrow(defaults::validate);
    }

    @ParameterizedTest @CsvSource({"1", "2", "3", "4"})
    void exactBudgetPerConcurrencyIsAcceptedAndOneByteMoreIsRejected(int concurrent) {
        long exact = BUDGET / concurrent; // 24 MiB, 12 MiB, 8 MiB, 6 MiB: all divide evenly
        assertEquals(BUDGET, exact * concurrent);
        assertDoesNotThrow(() -> properties(exact, concurrent).validate());
        assertThrows(IllegalArgumentException.class, () -> properties(exact + 1, concurrent).validate());
    }

    @ParameterizedTest @CsvSource({"1", "2", "3", "4"})
    void overflowingProductsAreRejected(int concurrent) {
        // Long.MAX_VALUE * 2 == -2 and (Long.MAX_VALUE / 2 + 1) * 2 == Long.MIN_VALUE: both used to pass.
        for (long maxBytes : new long[]{Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE / concurrent, Long.MAX_VALUE / 4 + 1}) {
            assertThrows(IllegalArgumentException.class, () -> properties(maxBytes, concurrent).validate(),
                    () -> "maxBytes=" + maxBytes + " maxConcurrent=" + concurrent);
        }
    }

    @Test void minimumBytesBoundaryAndConcurrencyRangeAreChecked() {
        assertDoesNotThrow(() -> properties(MIN_BYTES, 4).validate());
        assertThrows(IllegalArgumentException.class, () -> properties(MIN_BYTES - 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> properties(Long.MIN_VALUE, 1).validate());
        for (int concurrent : new int[]{0, -1, 5, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            // Includes the former overflow shape with an out-of-range divisor; no ArithmeticException either.
            assertThrows(IllegalArgumentException.class, () -> properties(Long.MAX_VALUE, concurrent).validate());
            assertThrows(IllegalArgumentException.class, () -> properties(MIN_BYTES, concurrent).validate());
        }
    }
}
