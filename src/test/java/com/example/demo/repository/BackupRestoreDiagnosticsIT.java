package com.example.demo.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.*;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class BackupRestoreDiagnosticsIT {
    private static final String CANARY = "synthetic-private-backup-diagnostic-canary";
    @Test void actualBackupFactoryCapturesWaitFailureBeforeCleanup(CapturedOutput output) throws Exception {
        MariaDBContainer configured = ReflectionTestUtils.invokeMethod(BackupRestoreIT.class, "container", CANARY);
        var marker = new JdbcDatabaseContainer.NoDriverFoundException("synthetic wait failure", null);
        try (var database = spy(configured)) {
            database.withTmpFs(Map.of("/var/lib/mysql", "rw"));
            // The real JDBC wait exits immediately through its existing non-retryable driver-error branch.
            doThrow(marker).when(database).createConnection(anyString());
            var failure = assertThrows(ContainerLaunchException.class, database::start);
            boolean retained = false;
            for (Throwable t = failure; t != null; t = t.getCause()) retained |= t == marker;
            assertTrue(retained, "Original wait cause must survive diagnostics and cleanup");
            var records = output.getErr().lines().filter(x -> x.startsWith("CONTAINER_DIAGNOSTIC "))
                    .map(x -> new JsonMapper().readTree(x.substring("CONTAINER_DIAGNOSTIC ".length()))).toList();
            assertTrue(records.stream().anyMatch(x -> "WAIT".equals(x.path("phase").asText())
                    && "WAIT_FAILED".equals(x.path("waitClass").asText())
                    && "running".equals(x.path("containerState").asText())), "Evidence must precede removal");
            assertNull(database.getContainerId(), "Original Testcontainers cleanup must still run");
            assertFalse(output.getAll().contains(CANARY), "Private fixture leaked (value withheld)");
        }
    }
}
