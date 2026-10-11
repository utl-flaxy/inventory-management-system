package com.example.demo.exception;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Response commit後にcontainerへ伝える例外にも秘密値を含めない。 */
public final class OrderCsvExportException extends RuntimeException {
    private final String failureType;
    private final String rootCauseType;
    private final String topFrame;

    public OrderCsvExportException(Exception failure) {
        super("CSV export interrupted"); // 意図的にraw cause/messageを保持しない。
        failureType = failure.getClass().getName();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable root = failure;
        seen.add(root);
        while (root.getCause() != null && seen.add(root.getCause())) root = root.getCause();
        rootCauseType = root.getClass().getName();
        topFrame = root.getStackTrace().length == 0 ? "unknown" : root.getStackTrace()[0].toString();
    }

    public String failureType() { return failureType; }
    public String rootCauseType() { return rootCauseType; }
    public String topFrame() { return topFrame; }
}
