package com.example.demo.exception;

/** The snapshot could not be spooled within the size limit or the transaction deadline. No CSV byte was sent. */
public class OrderCsvExportLimitExceededException extends RuntimeException {
    public static final String DETAIL = "CSV出力の上限（サイズまたはDB取得時間）を超えたため出力できません。データ量が上限を超えている場合、再試行では解消しません";

    public enum Reason { SIZE, TIME }

    private final Reason reason;

    public OrderCsvExportLimitExceededException(Reason reason) {
        super(DETAIL); // 意図的にraw causeを保持しない。
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
