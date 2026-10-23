package com.example.demo.exception;

/** Every export permit is in use. Rejected before any DB query, spool file or CSV header. */
public class OrderCsvExportBusyException extends RuntimeException {
    public static final String DETAIL = "他のCSV出力を処理中です。時間をおいて再試行してください";

    public OrderCsvExportBusyException() {
        super(DETAIL);
    }
}
