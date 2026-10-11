package com.example.demo.logging;

import ch.qos.logback.classic.LoggerContext;
import com.example.demo.TestApplicationLogs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggingTest {
    @Test
    void prodConfiguredBootConsoleEncoderProducesParseableJsonWithTopLevelMdc() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod").run(context -> {
                    assertNull(context.getStartupFailure()); // DB/本番secretを使わないlogging専用context。
                    Environment environment = context.getEnvironment();
                    assertEquals("logstash", environment.getProperty("logging.structured.format.console"));
                    var loggerContext = new LoggerContext();
                    loggerContext.putObject(Environment.class.getName(), environment);
                    var encoder = new StructuredLogEncoder();
                    encoder.setContext(loggerContext);
                    encoder.setFormat(environment.getRequiredProperty("logging.structured.format.console"));
                    encoder.start();
                    try (var logs = new TestApplicationLogs()) {
                        var response = new MockHttpServletResponse();
                        new RequestCorrelationFilter().doFilter(new MockHttpServletRequest("GET", "/products"),
                                response, (request, res) -> res.setContentType("application/json"));
                        String output = new String(encoder.encode(logs.events().get(0)), StandardCharsets.UTF_8);
                        var json = new ObjectMapper().readTree(output);
                        for (String field : java.util.List.of("@timestamp", "message", "level", "event", "requestId")) {
                            assertTrue(json.hasNonNull(field), field);
                        }
                        assertEquals("INFO", json.get("level").asText());
                        assertEquals("http.request.completed", json.get("event").asText());
                        assertEquals(response.getHeader("X-Request-Id"), json.get("requestId").asText());
                        assertEquals("/products", json.get("path").asText());
                        assertEquals(200, json.get("status").asInt());
                        assertFalse(json.has("stack_trace"));
                        logs.assertSafe();
                    } finally {
                        encoder.stop();
                        loggerContext.stop();
                    }
                });
    }
}
