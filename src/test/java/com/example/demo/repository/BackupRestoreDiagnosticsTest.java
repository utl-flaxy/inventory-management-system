package com.example.demo.repository;

import com.example.demo.support.ContainerDiagnostics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.*;
import org.springframework.boot.test.system.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
@Isolated("Uses BackupRestoreIT's existing static TempDir")
@Execution(ExecutionMode.SAME_THREAD)
class BackupRestoreDiagnosticsTest {
    private static final String CANARY = "synthetic-private-output-not-for-diagnostics";
    private static final JsonMapper JSON = new JsonMapper();
    @TempDir static Path directory;
    private static Object previousDirectory;
    @BeforeAll static void useDisposableScriptDirectory() {
        previousDirectory = ReflectionTestUtils.getField(BackupRestoreIT.class, "temp");
        ReflectionTestUtils.setField(BackupRestoreIT.class, "temp", directory);
    }
    @AfterAll static void restoreScriptDirectory() {
        ReflectionTestUtils.setField(BackupRestoreIT.class, "temp", previousDirectory);
    }
    private Object run(MariaDBContainer database, int exit, String output) throws Exception {
        var fixture = Files.createTempFile(directory, "fixture-", ".sh");
        Files.writeString(fixture, "printf '%s' \"$OUTPUT_CANARY\"\nexit " + exit + "\n");
        return ReflectionTestUtils.invokeMethod(new BackupRestoreIT(), "script", database,
                fixture.toAbsolutePath().toString().replace('\\', '/'), Map.of("OUTPUT_CANARY", output), new String[0]);
    }
    private static String diagnostic(CapturedOutput output) {
        return output.getErr().lines().filter(x -> x.startsWith("CONTAINER_DIAGNOSTIC "))
                .reduce((a,b) -> b).orElseThrow().substring("CONTAINER_DIAGNOSTIC ".length());
    }
    private static MariaDBContainer unavailableCollector() {
        var database = mock(MariaDBContainer.class);
        when(database.getDockerClient()).thenThrow(new AssertionError(CANARY));
        return database;
    }
    @Test void bothBackupDatabasesUseDiagnosticContainersAndBoundObservers() {
        var source = ReflectionTestUtils.getField(BackupRestoreIT.class, "source");
        var target = ReflectionTestUtils.getField(BackupRestoreIT.class, "target");
        assertTrue(source instanceof ContainerDiagnostics.MariaDb, "Source startup has no diagnostic hook");
        assertTrue(target instanceof ContainerDiagnostics.MariaDb, "Target startup has no diagnostic hook");
        var observers = Arrays.stream(BackupRestoreIT.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(RegisterExtension.class)).toList();
        assertEquals(2, observers.size());
        var bound = new ArrayList<Object>();
        for (var field : observers) {
            var observer = ReflectionTestUtils.getField(BackupRestoreIT.class, field.getName());
            assertTrue(observer instanceof ContainerDiagnostics.OnFailure);
            bound.add(((Supplier<?>) ReflectionTestUtils.getField(observer, "target")).get());
        }
        assertTrue(bound.stream().anyMatch(x -> x == source));
        assertTrue(bound.stream().anyMatch(x -> x == target));
    }
    @Test void successfulScriptDoesNotCollectEvidence(CapturedOutput output) throws Exception {
        var database = mock(MariaDBContainer.class);
        var result = run(database, 0, "synthetic success");
        int exit = ReflectionTestUtils.invokeMethod(result, "exit");
        String stream = ReflectionTestUtils.invokeMethod(result, "output");
        assertEquals(0, exit); assertEquals("synthetic success", stream);
        assertFalse(output.getErr().contains("CONTAINER_DIAGNOSTIC "));
        verifyNoInteractions(database);
    }
    @Test void nonzeroScriptRetainsExitAndStreamWhenDiagnosticsUnavailable(CapturedOutput output) throws Exception {
        var result = run(unavailableCollector(), 37, CANARY);
        int exit = ReflectionTestUtils.invokeMethod(result, "exit");
        String stream = ReflectionTestUtils.invokeMethod(result, "output");
        assertEquals(37, exit); assertTrue(CANARY.equals(stream), "Original subprocess output changed (withheld)");
        String line = diagnostic(output); var json = JSON.readTree(line);
        assertEquals(37, json.path("cliExit").asInt());
        assertEquals("CLI_NONZERO", json.path("failureClass").asText());
        assertEquals("FAILED_OR_BUSY", json.path("collectionStatus").asText());
        assertFalse(line.contains(CANARY)); assertFalse(output.getAll().contains(CANARY));
    }
    @Test void existingSecretAssertionStillFailsAfterUnavailableDiagnostics(CapturedOutput output) {
        String privateFixture = (String) ReflectionTestUtils.getField(BackupRestoreIT.class, "USERNAME");
        var original = assertThrows(AssertionError.class, () -> run(unavailableCollector(), 37, privateFixture));
        assertEquals("Sensitive output detected (value withheld)", original.getMessage().split(" ==> ")[0]);
        assertEquals(0, original.getSuppressed().length);
        String line = diagnostic(output);
        assertEquals(37, JSON.readTree(line).path("cliExit").asInt());
        assertFalse(output.getAll().contains(privateFixture)); assertFalse(line.contains(CANARY));
    }
}
