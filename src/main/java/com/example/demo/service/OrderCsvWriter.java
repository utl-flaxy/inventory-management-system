package com.example.demo.service;

import com.example.demo.dto.OrderCsvRow;
import com.example.demo.entity.OrderStatus;

import java.io.IOException;
import java.io.Writer;

/** RFC 4180の引用処理と、保存値を変更しないspreadsheet向けのtext化。 */
public final class OrderCsvWriter {
    private OrderCsvWriter() {}

    public static void header(Writer writer) throws IOException {
        writer.write("注文ID,商品名,数量,単価,合計金額,ステータス\r\n");
    }

    public static void row(Writer writer, OrderCsvRow row) throws IOException {
        writer.write(row.orderId().toString());
        writer.write(',');
        text(writer, row.productName());
        writer.write(',' + row.quantity().toString() + ',' + row.unitPrice().toString()
                + ',' + row.lineTotal() + ',' + OrderStatus.valueOf(row.status()).name() + "\r\n");
    }

    private static void text(Writer writer, String value) throws IOException {
        boolean dangerous = dangerous(value);
        boolean quoted = dangerous || value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (quoted) writer.write('"');
        // apostropheでtext化し、Excelで再保存時にapostropheが除かれてもtabが残る。
        // 制御文字/空白の後の式記号も検出する。prefixはCSVセルだけに付ける。
        if (dangerous) writer.write("'\t");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') writer.write('"');
            writer.write(c);
        }
        if (quoted) writer.write('"');
    }

    private static boolean dangerous(String value) {
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            // tab/CR/LF自体もspreadsheet importerに特別扱いされる。
            if (c == '\t' || c == '\r' || c == '\n') return true;
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)
                    && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) {
                return "=+-@＝＋－＠".indexOf(c) >= 0;
            }
            i += Character.charCount(c);
        }
        return false;
    }
}
