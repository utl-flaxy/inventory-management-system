package com.example.demo.exception;

import com.example.demo.TestApplicationLogs;
import com.example.demo.logging.RequestCorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.*;
import java.util.List;
import java.util.stream.Stream;

import static com.example.demo.TestApiProblems.problem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class ServerFailureResponseTest {
    private static final String CANARY = "private-db-failure-canary";
    private final Probe probe = new Probe();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(probe).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestCorrelationFilter()).build();
    }

    public static Stream<Arguments> failures() {
        return Stream.of(
                unavailable(new DataAccessResourceFailureException(CANARY, new SQLTransientConnectionException(CANARY))),
                unavailable(new CannotCreateTransactionException(CANARY, new SQLTransientConnectionException(CANARY))),
                unavailable(new CannotGetJdbcConnectionException(CANARY, new SQLRecoverableException(CANARY))),
                unavailable(new DataAccessResourceFailureException(CANARY, new SQLNonTransientConnectionException(CANARY, "08006"))),
                unavailable(new TransientDataAccessResourceException(CANARY)),
                unavailable(new QueryTimeoutException(CANARY)),
                unavailable(new DataAccessResourceFailureException(CANARY, new SQLTimeoutException(CANARY))),
                unexpected(new DataAccessResourceFailureException(CANARY)),
                unexpected(new CannotCreateTransactionException(CANARY, new IllegalStateException(CANARY))),
                unexpected(new DataIntegrityViolationException(CANARY)),
                unexpected(new InvalidDataAccessResourceUsageException(CANARY, new SQLSyntaxErrorException(CANARY, "42000"))),
                unexpected(new CannotGetJdbcConnectionException(CANARY, new SQLTransientConnectionException(CANARY, null,
                        new SQLInvalidAuthorizationSpecException(CANARY, "28000")))),
                unexpected(new IllegalArgumentException(CANARY)),
                unexpected(new IllegalStateException(CANARY)));
    }

    private static Arguments unavailable(RuntimeException failure) {
        return Arguments.of(failure, 503, "SERVICE_UNAVAILABLE", "Service Unavailable");
    }

    private static Arguments unexpected(RuntimeException failure) {
        return Arguments.of(failure, 500, "SYSTEM_ERROR", "System Error");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mvcOnlyClassifiesSupportedAvailabilityFailures(RuntimeException failure, int status, String code, String title) throws Exception {
        probe.failure = failure;
        try (var logs = new TestApplicationLogs()) {
            var result = mvc.perform(get("/server-failure-probe")).andExpect(problem(status, code, title, "/server-failure-probe"))
                    .andExpect(jsonPath("$.detail").value(status == 503 ? ApiProblemFactory.SERVICE_UNAVAILABLE_DETAIL : ApiProblemFactory.SYSTEM_ERROR_DETAIL))
                    .andExpect(header().doesNotExist("Retry-After")).andReturn();
            var events = logs.events(status == 503 ? "system.database_unavailable" : "system.unhandled_error");
            assertEquals(1, events.size());
            assertEquals(result.getResponse().getHeader("X-Request-Id"), events.get(0).getMDCPropertyMap().get("requestId"));
            for (var event : logs.events()) {
                assertNull(event.getThrowableProxy());
                for (String secret : List.of(CANARY, "jdbc:", "password="))
                    assertFalse((event.getFormattedMessage() + TestApplicationLogs.fields(event)).contains(secret));
            }
        }
    }

    @RestController
    static class Probe {
        RuntimeException failure;
        @GetMapping("/server-failure-probe") void fail() { throw failure; }
    }
}
