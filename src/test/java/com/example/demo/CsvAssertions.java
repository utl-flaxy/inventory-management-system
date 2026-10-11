package com.example.demo;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class CsvAssertions {
    private CsvAssertions() {}

    public static List<List<String>> parse(String csv) throws IOException {
        try (var rows = new CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY).readerFor(String[].class)
                .with(CsvSchema.emptySchema()).<String[]>readValues(csv)) {
            List<List<String>> result = rows.readAll().stream().map(Arrays::asList).toList();
            assertEquals(List.of("注文ID", "商品名", "数量", "単価", "合計金額", "ステータス"), result.get(0));
            result.forEach(row -> assertEquals(6, row.size()));
            return result;
        }
    }
}
