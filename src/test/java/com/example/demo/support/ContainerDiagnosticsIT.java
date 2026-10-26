package com.example.demo.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ContainerDiagnosticsIT {
    private static final JsonMapper JSON = new JsonMapper();
    private static JsonNode last(CapturedOutput output) {
        return output.getErr().lines().filter(line -> line.startsWith("CONTAINER_DIAGNOSTIC "))
                .map(line -> JSON.readTree(line.substring("CONTAINER_DIAGNOSTIC ".length()))).reduce((a,b) -> b).orElseThrow();
    }
    @Test void realMariaDbCliFailureRetainsCertificateClockAndStateAfterCleanup(CapturedOutput output) throws Exception {
        try (var db = new ContainerDiagnostics.MariaDb("mariadb:12.2.2")) {
            db.withTmpFs(Map.of("/var/lib/mysql", "rw"));
            db.start();
            var result = ContainerDiagnostics.exec(db, ContainerDiagnostics.Kind.MARIADB, "sh", "-c", "exit 23");
            assertEquals(23, result.getExitCode());
            var evidence = last(output);
            assertEquals(23, evidence.path("cliExit").asInt());
            assertEquals("running", evidence.path("containerState").asText());
            assertEquals("COMPLETE", evidence.path("collectionStatus").asText());
            assertEquals("VALID", evidence.path("clock").path("bracketStatus").asText());
            assertFalse(evidence.path("certificates").path("server").path("notBeforeUtc").asText().isBlank());
            assertFalse(evidence.path("certificates").path("server").path("notAfterUtc").asText().isBlank());
            assertEquals("2.0.5", evidence.path("testcontainersVersion").asText());
        }
        // The value-free record outlives the removed disposable container.
        assertEquals(23, last(output).path("cliExit").asInt());
        assertFalse(last(output).toString().contains("BEGIN CERTIFICATE"));
    }
    @Test void failingWaitIsRecordedBeforeAutomaticRemovalAndStillFails(CapturedOutput output) {
        var marker = new IllegalStateException("synthetic wait failure");
        try (var container = new ContainerDiagnostics.Caddy("mariadb:12.2")) {
            container.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                    .withCommand("-c", "exec tail -f /dev/null")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .waitingFor(new AbstractWaitStrategy() { @Override protected void waitUntilReady() { throw marker; } });
            var failure = assertThrows(ContainerLaunchException.class, container::start);
            boolean found = false; for (Throwable t = failure; t != null; t = t.getCause()) found |= t == marker;
            assertTrue(found, "The original wait failure must remain in the cause chain");
            var evidence = last(output);
            assertEquals("WAIT", evidence.path("phase").asText());
            assertEquals("WAIT_FAILED", evidence.path("waitClass").asText());
            assertEquals("running", evidence.path("containerState").asText());
            assertNull(container.getContainerId(), "Testcontainers must still perform its original cleanup");
        }
    }
    @Test void processExitIsRecordedBeforeCleanupWithoutClaimingReadiness(CapturedOutput output) {
        try (var container = new ContainerDiagnostics.Caddy("mariadb:12.2")) {
            container.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                    .withCommand("-c", "exit 17").withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage("UNREACHABLE_DIAGNOSTIC_READY", 1))
                    .withStartupTimeout(java.time.Duration.ofSeconds(2));
            assertThrows(ContainerLaunchException.class, container::start);
            var evidence = last(output);
            assertTrue(java.util.Set.of("STARTUP_FAILED", "WAIT_FAILED").contains(evidence.path("waitClass").asText()));
            assertEquals(17, evidence.path("containerExit").asInt());
            assertEquals("exited", evidence.path("containerState").asText());
            assertNull(container.getContainerId());
        }
    }
}
