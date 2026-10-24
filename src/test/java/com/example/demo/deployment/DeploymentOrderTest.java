package com.example.demo.deployment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #157: deployment ordering against a real disposable Git history (bare origin + deploy clone).
 * Only docker/curl/flock/sleep are mocked; no network, credentials, SSH, AWS or real Docker.
 */
class DeploymentOrderTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ci-completion-reversed", "first-deploy", "same-sha", "forward",
            "forward-past-intermediate-then-late-intermediate", "other-branch", "rewritten-main",
            "missing-marker", "marker-mismatch", "malformed-marker", "unlabeled-running-image",
            "unknown-running-commit", "marker-without-container", "running-changed-during-build", "ancestry-git-error"})
    void deploysOnlyForwardAlongOriginMainFromTheVerifiedRunningRevision(String scenario) throws Exception {
        var output = Files.createTempFile("deployment-order-test-", ".log");
        try {
            var process = new ProcessBuilder(DeploymentScriptTest.bash(), "scripts/tests/deploy-order-test.sh",
                    "scripts/deploy.sh", scenario).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "Ordering scenario must have a finite timeout: " + scenario);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception e) { return e.toString(); }
            });
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
