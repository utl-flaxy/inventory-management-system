package com.example.demo.service;

import com.example.demo.dto.OrderCsvRow;
import com.example.demo.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;

import static com.example.demo.CsvAssertions.parse;
import static org.junit.jupiter.api.Assertions.*;

class OrderCsvWriterTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "日本語の商品🍎", "apple,orange", "say \"hello\"", "line\nnext",
            "line\r\nnext", "line\rnext", "normal = text", "  normal", "'normal"})
    void csvParserRoundtripPreservesSafeText(String name) throws Exception {
        assertEquals(name, cell(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+SUM(A1)", "-1+2", "@SUM(A1)", " =1", "\t=1", "\r=1", "\n=1",
            "\r\n@x", "\u0000=1", "\u001b+1", "\u0085-1", "\u00a0@x", "\u2003=1", "\u200b=1",
            "\ufeff=1", "＝1+1", "＋1", "－1", "＠x", "\tplain", "\rplain", "\nplain", "=1,\"x\"\r\nnext"})
    void spreadsheetTextPrefixIsSeparateFromCsvQuoting(String name) throws Exception {
        assertEquals("'\t" + name, cell(name));
    }

    private String cell(String name) throws Exception {
        var output = new StringWriter();
        OrderCsvWriter.header(output);
        OrderCsvWriter.row(output, new OrderCsvRow(7L, 9L, name, 2, 3_000_000_000L, OrderStatus.SHIPPED.name()));
        var rows = parse(output.toString());
        assertEquals(2, rows.size());
        assertEquals(java.util.List.of("7", rows.get(1).get(1), "2", "3000000000", "6000000000", "SHIPPED"), rows.get(1));
        assertTrue(output.toString().endsWith("\r\n"));
        return rows.get(1).get(1);
    }

    @Test
    void lineTotalOverflowIsNotSilentlyExported() {
        assertThrows(ArithmeticException.class, () -> OrderCsvWriter.row(new StringWriter(),
                new OrderCsvRow(1L, 1L, "item", 2, Long.MAX_VALUE, OrderStatus.PENDING.name())));
    }
}
