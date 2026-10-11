package com.example.demo.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BackupRestoreScriptTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"backup-success", "backup-default", "backup-dump-failure", "backup-gzip-failure",
            "backup-checksum-failure", "backup-manifest-failure", "backup-publish-failure", "backup-invalid-db",
            "backup-system-db", "backup-unavailable", "backup-unsupported-schema", "restore-success", "tamper",
            "checksum-path", "invalid-gzip", "unknown-version", "unknown-field", "duplicate-field", "invalid-source",
            "invalid-target", "system-target", "name-mismatch", "non-empty", "wrong-confirm", "missing-confirm",
            "confirm-without-replace", "replace", "app-running", "app-lookup-failure", "production-stopped",
            "missing-db", "artifact-symlink", "missing-arguments", "import-failure", "ping-failure", "query-failure",
            "invalid-count"})
    void scriptsEnforceSafetyWithoutRealDocker(String scenario) throws Exception {
        var output = Files.createTempFile("backup-restore-mock-", ".log");
        try {
            var process = new ProcessBuilder(DeploymentScriptTest.bash(), "scripts/tests/backup-restore-test.sh",
                    "scripts/db", scenario).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "Mock scenario timed out: " + scenario);
            // output/dump/argvはassertion failureにも表示しない。
            assertEquals(0, process.exitValue(), "Backup/restore safety scenario failed: " + scenario);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void backupUsesPrivateConsistentAtomicBundles() throws Exception {
        var script = Files.readString(Path.of("scripts/db/backup.sh"));
        for (var required : new String[]{"set -euo pipefail", "umask 077", "$HOME/inventory-management-system-backups",
                "mariadb-dump", "--protocol=socket", "--user=root", "--single-transaction", "--quick",
                "--skip-lock-tables", "--triggers", "--default-character-set=utf8mb4", "gzip -n", "sha256sum",
                "chmod 0700", "chmod 0600", "mv -T", "MARIADB_DATABASE", "manifest.properties"}) {
            assertTrue(script.contains(required), "Missing backup contract: " + required);
        }
        assertTrue(script.indexOf("mv -T") > script.indexOf("chmod 0600"));
    }

    @Test
    void restoreVerifiesBeforeConnectingAndNeverDeletesVolumes() throws Exception {
        var script = Files.readString(Path.of("scripts/db/restore.sh"));
        assertTrue(script.indexOf("sha256sum --check") < script.indexOf("docker inspect"));
        for (var required : new String[]{"set -euo pipefail", "umask 077", "--replace", "--confirm-db",
                "information_schema.TABLES", "/inventory-db", "inventory-app", "gzip -dc", "valid_database"}) {
            assertTrue(script.contains(required));
        }
        assertFalse(script.contains("volume rm"));
        assertFalse(script.contains("down -v"));
    }

    @Test
    void secretsAreExpandedOnlyByContainerShellWithoutTracingOrEnvironmentDump() throws Exception {
        for (String name : new String[]{"backup.sh", "restore.sh", "common.sh"}) {
            var script = Files.readString(Path.of("scripts/db", name));
            assertFalse(script.contains("set -x"));
            assertFalse(script.contains("docker exec -e"));
            assertFalse(script.contains(".Config.Env"));
            assertFalse(script.contains("--password="));
        }
        assertTrue(Files.readString(Path.of("scripts/db/common.sh"))
                .contains("export MYSQL_PWD=\"$MARIADB_ROOT_PASSWORD\""));
    }
}
