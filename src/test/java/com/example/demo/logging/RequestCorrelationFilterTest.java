package com.example.demo.logging;

import com.example.demo.TestApplicationLogs;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import static com.example.demo.TestApplicationLogs.fields;
import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 403, 404, 409, 415, 500})
    void serverUuidCoversTheChainAndCompletionWithActualStatus(int status) throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("X-Request-Id", "client-spoofed-id");
        var response = new MockHttpServletResponse();
        try (var logs = new TestApplicationLogs()) {
            filter.doFilter(request, response, (req, res) -> {
                String id = response.getHeader("X-Request-Id");
                assertEquals(id, UUID.fromString(id).toString());
                assertEquals(4, UUID.fromString(id).version());
                assertNotEquals("client-spoofed-id", id);
                assertEquals(id, MDC.get("requestId"));
                response.setStatus(status);
            });
            assertNull(MDC.get("requestId"));
            var event = logs.events("http.request.completed").get(0);
            assertEquals(1, logs.events().size());
            assertEquals(response.getHeader("X-Request-Id"), event.getMDCPropertyMap().get("requestId"));
            assertEquals(Set.of("event", "method", "path", "status", "durationMs"), fields(event).keySet());
            assertEquals("POST", fields(event).get("method"));
            assertEquals("/auth/login", fields(event).get("path"));
            assertEquals(status, fields(event).get("status"));
            assertTrue((long) fields(event).get("durationMs") >= 0);
            logs.assertSafe("client-spoofed-id");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "servlet", "io", "error"})
    void escapingFailuresStillCleanMdcAndAreNotLoggedAs200(String kind) {
        var request = new MockHttpServletRequest("GET", "/products");
        var response = new MockHttpServletResponse();
        try (var logs = new TestApplicationLogs()) {
            assertThrows(Throwable.class, () -> filter.doFilter(request, response, (req, res) -> {
                assertNotNull(MDC.get("requestId"));
                switch (kind) {
                    case "runtime" -> throw new RuntimeException("super-secret-password");
                    case "servlet" -> throw new ServletException("super-secret-password");
                    case "io" -> throw new IOException("super-secret-password");
                    default -> throw new AssertionError("super-secret-password");
                }
            }));
            assertNull(MDC.get("requestId"));
            assertEquals(1, logs.events("http.request.completed").size());
            assertEquals(500, fields(logs.events().get(0)).get("status"));
            assertEquals(200, response.getStatus()); // loggingだけでresponseを変更しない。
            logs.assertSafe();
        }
    }

    @Test
    void successiveRequestsHaveDistinctIdsWithoutLeakingOtherMdcKeys() throws Exception {
        MDC.put("testContext", "retained");
        try (var logs = new TestApplicationLogs()) {
            var first = new MockHttpServletResponse();
            var second = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/products"), first, (req, res) -> { });
            assertNull(MDC.get("requestId"));
            filter.doFilter(new MockHttpServletRequest("GET", "/products"), second, (req, res) -> { });
            assertNull(MDC.get("requestId"));
            assertNotEquals(first.getHeader("X-Request-Id"), second.getHeader("X-Request-Id"));
            assertEquals("retained", MDC.get("testContext"));
            assertEquals(2, logs.events().size());
        } finally {
            MDC.remove("testContext");
        }
    }

    @Test
    void nestedFilterInvocationProducesOnlyOneCompletion() throws Exception {
        var request = new MockHttpServletRequest("GET", "/products");
        var response = new MockHttpServletResponse();
        try (var logs = new TestApplicationLogs()) {
            filter.doFilter(request, response, (req, res) ->
                    filter.doFilter(req, res, (innerRequest, innerResponse) -> assertNotNull(MDC.get("requestId"))));
            assertEquals(1, logs.events().size());
            assertNull(MDC.get("requestId"));
        }
    }

    @Test
    void headersQueryAndBodiesAreNeitherReadNorLogged() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/login") {
            @Override public String getQueryString() { fail("Query must not be read"); return null; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { fail("Body must not be read"); return null; }
            @Override public java.io.BufferedReader getReader() { fail("Body must not be read"); return null; }
        };
        request.setQueryString("password=query-private");
        request.addHeader("Authorization", "Bearer very-secret-jwt");
        request.addHeader("Idempotency-Key", "idempotency-secret-key");
        request.addHeader("Cookie", "secret-cookie");
        request.setContent("super-secret-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        try (var logs = new TestApplicationLogs()) {
            filter.doFilter(request, response, (req, res) -> {
                assertSame(request, req);
                assertSame(response, res);
                res.getWriter().write("private-response-body");
            });
            assertEquals("/auth/login", fields(logs.events().get(0)).get("path"));
            logs.assertSafe("query-private", "secret-cookie", "private-response-body");
        }
    }
}
