package com.example.demo.exception;

import ch.qos.logback.classic.Level;
import com.example.demo.TestApplicationLogs;
import com.example.demo.logging.RequestCorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

import static com.example.demo.TestApplicationLogs.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SafeExceptionLoggingTest {
    private final Probe probe = new Probe();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(probe).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestCorrelationFilter()).build();
    }

    @Test
    void secretBearing500LogsOnlyTypesAndRootFrameAndPreservesSafeResponse() throws Exception {
        var root = new IllegalStateException("jdbc:private SQL uk_users_username private-test-hash");
        root.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.SafeService", "execute", "SafeService.java", 42)});
        probe.failure = new RuntimeException("super-secret-password Bearer very-secret-jwt idempotency-secret-key requestFingerprint", root);
        try (var logs = new TestApplicationLogs()) {
            var result = mvc.perform(get("/logging-probe/failure").queryParam("password", "query-private"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SYSTEM_ERROR"))
                    .andExpect(jsonPath("$.detail").value(ApiProblemFactory.SYSTEM_ERROR_DETAIL))
                    .andExpect(jsonPath("$.instance").value("/logging-probe/failure")).andReturn();
            assertEquals(1, logs.events("system.unhandled_error").size());
            var event = logs.events("system.unhandled_error").get(0);
            assertEquals(Level.ERROR, event.getLevel());
            assertEquals(Map.of("event", "system.unhandled_error", "exceptionType", RuntimeException.class.getName(),
                    "rootCauseType", IllegalStateException.class.getName(), "topFrame", "example.SafeService.execute(SafeService.java:42)"), fields(event));
            assertEquals(result.getResponse().getHeader("X-Request-Id"), event.getMDCPropertyMap().get("requestId"));
            assertEquals(500, fields(logs.events("http.request.completed").get(0)).get("status"));
            logs.assertSafe("query-private");
            String response = result.getResponse().getContentAsString();
            for (String value : java.util.List.of("SQL", "jdbc:", "private-test-hash", "super-secret-password", "RuntimeException", "topFrame")) {
                assertFalse(response.contains(value));
            }
        }
    }

    @Test
    void cyclicCauseChainTerminatesWithoutMessagesOrThrowableLogging() {
        var outer = new RuntimeException("super-secret-password");
        var inner = new IllegalStateException("private-test-hash");
        outer.initCause(inner);
        inner.initCause(outer);
        try (var logs = new TestApplicationLogs()) {
            var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    new GlobalExceptionHandler().handleException(outer, new org.springframework.mock.web.MockHttpServletRequest()));
            assertEquals(500, result.getStatusCode().value());
            assertEquals(IllegalStateException.class.getName(), fields(logs.events().get(0)).get("rootCauseType"));
            logs.assertSafe();
        }
    }

    @Test
    void exceptionWithoutFramesUsesSafeUnknownMarker() throws Exception {
        probe.failure = new RuntimeException("super-secret-password");
        probe.failure.setStackTrace(new StackTraceElement[0]);
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(get("/logging-probe/failure")).andExpect(status().isInternalServerError());
            assertEquals("unknown", fields(logs.events("system.unhandled_error").get(0)).get("topFrame"));
            logs.assertSafe();
        }
    }

    @ParameterizedTest
    @MethodSource("com.example.demo.exception.ProblemDetailContractTest#businessProblems")
    void knownBusiness4xxIncludingAllConflictsNeverProduceSystemError(RuntimeException failure, int status, String code, String title) throws Exception {
        probe.failure = failure;
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(get("/logging-probe/failure")).andExpect(status().is(status))
                    .andExpect(jsonPath("$.code").value(code));
            assertTrue(logs.events("system.unhandled_error").isEmpty());
            assertEquals(1, logs.events().size());
            assertEquals(status, fields(logs.events().get(0)).get("status"));
            logs.assertSafe();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 405, 415})
    void standardMvc4xxRemainNonErrorEvents(int status) throws Exception {
        var request = switch (status) {
            case 400 -> get("/logging-probe/number");
            case 405 -> put("/logging-probe/body");
            default -> post("/logging-probe/body").contentType(MediaType.TEXT_PLAIN).content("super-secret-password");
        };
        try (var logs = new TestApplicationLogs()) {
            mvc.perform(request).andExpect(status().is(status));
            assertEquals(1, logs.events().size());
            assertTrue(logs.events("system.unhandled_error").isEmpty());
            logs.assertSafe();
        }
    }

    @RestController
    static class Probe {
        RuntimeException failure;
        @GetMapping("/logging-probe/failure") void failure() { throw failure; }
        @GetMapping("/logging-probe/number") void number(@RequestParam Long id) { }
        @PostMapping(value = "/logging-probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody Map<String, Object> body) { }
    }
}
