package com.example.demo.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.cert.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ContainerDiagnosticsTest {
    private String previousMode;
    @org.junit.jupiter.api.BeforeEach void defaultDiagnosticMode() {
        previousMode = System.getProperty("test.container.diagnostics");
        System.clearProperty("test.container.diagnostics");
    }
    @org.junit.jupiter.api.AfterEach void restoreDiagnosticMode() {
        if (previousMode == null) System.clearProperty("test.container.diagnostics");
        else System.setProperty("test.container.diagnostics", previousMode);
    }
    private static final JsonMapper JSON = new JsonMapper();
    private static final String CANARY = "PRIVATE-password-JWT-key-DO-NOT-EMIT";
    private static final Instant NOW = Instant.parse("2026-10-25T11:58:44.528Z");
    private static ContainerDiagnostics.Mark mark() { return ContainerDiagnostics.Mark.now(); }

    @Test void certificatePeriodsAreUtcRegardlessOfDisplayZone() throws Exception {
        var cert = mock(X509Certificate.class);
        when(cert.getNotBefore()).thenReturn(Date.from(OffsetDateTime.parse("2026-10-25T20:58:45+09:00").toInstant()));
        when(cert.getNotAfter()).thenReturn(Date.from(OffsetDateTime.parse("2036-10-22T20:58:45+09:00").toInstant()));
        assertEquals(Map.of("notBeforeUtc", "2026-10-25T11:58:45Z", "notAfterUtc", "2036-10-22T11:58:45Z"),
                ContainerDiagnostics.certificate(cert));
        verify(cert, never()).getEncoded();
    }
    @Test void clockDifferenceIsAnIntervalAndFractionalEpochIsNotRounded() {
        var result = ContainerDiagnostics.clock(NOW, NOW.plusMillis(32), 16_644_400L, "1792929525.116837524");
        assertEquals("2026-10-25T11:58:45.116837524Z", result.get("containerUtc"));
        assertEquals(556_837_524L, result.get("guestMinusHostMinNanos"));
        assertEquals(588_837_524L, result.get("guestMinusHostMaxNanos"));
        assertEquals(16_644_400L, result.get("observationElapsedNanos"));
    }
    @Test void negativeClockOffsetIsNotMadeAbsolute() {
        var result = ContainerDiagnostics.clockValues(NOW, NOW.plusMillis(10), 7L, NOW.minusMillis(500));
        assertEquals(-510_000_000L, result.get("guestMinusHostMinNanos"));
        assertEquals(-500_000_000L, result.get("guestMinusHostMaxNanos"));
    }
    @Test void reversedWallClockIsNotAValidOffsetBracket() {
        var result = ContainerDiagnostics.clockValues(NOW, NOW.minusMillis(1), 17L, NOW);
        assertEquals("WALL_CLOCK_REVERSED", result.get("bracketStatus"));
        assertFalse(result.containsKey("guestMinusHostMinNanos"));
        assertEquals(17L, result.get("observationElapsedNanos"));
    }
    @Test void elapsedUsesOnlyTheMonotonicClock() {
        assertEquals(5L, new ContainerDiagnostics.Mark(NOW.minusSeconds(10000), 100).elapsed(105));
        assertEquals(5L, new ContainerDiagnostics.Mark(NOW.plusSeconds(10000), 100).elapsed(105));
    }
    @Test void malformedClockInputIsRejectedWithoutReflection() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ContainerDiagnostics.clock(NOW, NOW, 1, CANARY));
        assertFalse(error.getMessage().contains(CANARY));
    }
    @Test void jdbcCauseAndNextChainsAreBoundedAndMessagesAreNotEvidence() {
        var first = new SQLException(CANARY, "08000");
        var next = new SQLNonTransientConnectionException(CANARY, "08S01");
        first.setNextException(next); next.initCause(first);
        var result = ContainerDiagnostics.classify(new RuntimeException(CANARY, first));
        assertEquals(Set.of("08000", "08S01"), result.get("sqlStates"));
        assertFalse(JSON.writeValueAsString(result).contains(CANARY));
        assertTrue(((List<?>) result.get("exceptionClasses")).contains("java.sql.SQLNonTransientConnectionException"));
    }
    @Test void tlsNotBeforeIsClassifiedWithoutParsingExceptionText() {
        var sql = new SQLException(CANARY, "08000", new CertificateNotYetValidException(CANARY));
        assertEquals("TLS_NOT_YET_VALID", ContainerDiagnostics.classify(sql).get("failureClass"));
        assertEquals("TLS_EXPIRED", ContainerDiagnostics.classify(new CertificateExpiredException(CANARY)).get("failureClass"));
    }
    @Test void arbitrarySqlStateAndCliOutputCannotLeak() {
        var out = new AtomicReference<String>();
        ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.CLI, mark(),
                new SQLException(CANARY, "TOKEN"), 23, CANARY + " certificate is not yet valid",
                map -> map.put("containerState", "running"), out::set);
        var json = JSON.readTree(out.get());
        assertEquals(23, json.path("cliExit").asInt());
        assertEquals("TLS_NOT_YET_VALID", json.path("cliClass").asText());
        assertTrue(json.path("sqlStates").isEmpty());
        assertFalse(out.get().contains(CANARY)); assertFalse(out.get().contains("TOKEN"));
    }
    @Test void collectorFailureIsAnAvailabilityResultNotAReplacementException() {
        var original = new AssertionError(CANARY);
        var output = new AtomicReference<String>();
        ContainerDiagnostics.record(ContainerDiagnostics.Kind.MARIADB, ContainerDiagnostics.Phase.WAIT, mark(),
                original, null, null, map -> { throw new AssertionError(CANARY); }, output::set);
        assertEquals("FAILED_OR_BUSY", JSON.readTree(output.get()).path("collectionStatus").asText());
        assertEquals("WAIT_FAILED", JSON.readTree(output.get()).path("waitClass").asText());
        assertFalse(output.get().contains(CANARY)); assertEquals(0, original.getSuppressed().length);
    }
    @Test void sinkFailureCannotEscape() {
        assertDoesNotThrow(() -> ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST,
                mark(), new AssertionError(CANARY), null, null, map -> {}, line -> { throw new AssertionError(CANARY); }));
    }
    @Test void exceptionHandlerPreservesTheExactOriginalEvenWhenTargetLookupFails() {
        var original = new AssertionError(CANARY);
        var extension = new ContainerDiagnostics.OnFailure(() -> { throw new IllegalStateException(CANARY); }, ContainerDiagnostics.Kind.CADDY);
        assertSame(original, assertThrows(AssertionError.class, () -> extension.handleTestExecutionException(null, original)));
    }
    @Test void successfulCliDoesNotInspectCopyCertificatesOrRunAdditionalCommands() throws Exception {
        var container = mock(GenericContainer.class);
        var expected = mock(Container.ExecResult.class);
        when(expected.getExitCode()).thenReturn(0); when(expected.getStdout()).thenReturn(CANARY); when(expected.getStderr()).thenReturn(CANARY);
        when(container.execInContainer("fixture")).thenReturn(expected);
        assertSame(expected, ContainerDiagnostics.exec(container, ContainerDiagnostics.Kind.CADDY, "fixture"));
        verify(container).execInContainer("fixture"); verifyNoMoreInteractions(container);
    }
    @Test void nonzeroCliIsReturnedUnchangedWithoutPublishingItsStreams(CapturedOutput output) throws Exception {
        var container = mock(GenericContainer.class);
        var expected = mock(Container.ExecResult.class);
        when(expected.getExitCode()).thenReturn(7); when(expected.getStdout()).thenReturn(CANARY); when(expected.getStderr()).thenReturn(CANARY);
        when(container.execInContainer("fixture")).thenReturn(expected);
        assertSame(expected, ContainerDiagnostics.exec(container, ContainerDiagnostics.Kind.CADDY, "fixture"));
        assertTrue(output.getErr().contains("\"cliExit\":7"));
        assertFalse(output.getErr().contains(CANARY));
        verify(container, never()).getLogs(); verify(container, never()).getEnv();
    }
    @Test void ioExceptionIsRethrownByIdentity(CapturedOutput output) throws Exception {
        var container = mock(GenericContainer.class); var failure = new IOException(CANARY);
        when(container.execInContainer("fixture")).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class,
                () -> ContainerDiagnostics.exec(container, ContainerDiagnostics.Kind.CADDY, "fixture")));
        assertFalse(output.getErr().contains(CANARY));
    }
    @Test void explicitModeObservesSuccessWithoutChangingItsResult(CapturedOutput output) throws Exception {
        System.setProperty("test.container.diagnostics", "always");
        var container = mock(GenericContainer.class); var expected = mock(Container.ExecResult.class);
        when(expected.getExitCode()).thenReturn(0); when(expected.getStderr()).thenReturn("");
        when(container.execInContainer("fixture")).thenReturn(expected);
        assertSame(expected, ContainerDiagnostics.exec(container, ContainerDiagnostics.Kind.CADDY, "fixture"));
        assertTrue(output.getErr().contains("\"cliExit\":0"));
        verify(container, never()).getEnv(); verify(container, never()).getLogs();
    }
    @Test void slowDiagnosticIsCancelledWithoutUnboundedWaitingOrRetry() {
        var output = new AtomicReference<String>();
        ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST, mark(),
                new AssertionError(CANARY), null, null, map -> {
                    try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, output::set);
        assertEquals("TIME_BUDGET", JSON.readTree(output.get()).path("collectionStatus").asText());
    }
}
