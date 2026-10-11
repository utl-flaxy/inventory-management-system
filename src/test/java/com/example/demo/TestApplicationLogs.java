package com.example.demo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Application loggerだけをDEBUGでcaptureする。framework/DBの既存ログは対象外。
public final class TestApplicationLogs implements AutoCloseable {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.example.demo");
    private final Level previous = logger.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing(); // finallyのMDC cleanup前にsnapshotを固定する。
            super.append(event);
        }
    };

    public TestApplicationLogs() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    public List<ILoggingEvent> events() { return List.copyOf(appender.list); }

    public List<ILoggingEvent> events(String name) {
        return events().stream().filter(event -> name.equals(fields(event).get("event"))).toList();
    }

    public static Map<String, Object> fields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        }
        return fields;
    }

    public void assertSafe(String... additionalSecrets) {
        var forbidden = new java.util.ArrayList<>(List.of("Bearer very-secret-jwt", "super-secret-password",
                "private-test-hash", "idempotency-secret-key", "requestFingerprint", "jdbc:", "SQL",
                "uk_users_username", "Authorization", "Cookie", "Idempotency-Key"));
        forbidden.addAll(List.of(additionalSecrets));
        for (ILoggingEvent event : events()) {
            assertNull(event.getThrowableProxy(), "No throwable/full stack trace");
            assertNotNull(fields(event).get("event"), "Every application event has a stable key");
            String rendered = event.getFormattedMessage() + fields(event) + event.getMDCPropertyMap();
            for (String secret : forbidden) assertFalse(rendered.contains(secret), "Forbidden log value: " + secret);
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
        appender.stop();
    }
}
