package com.example.demo.deployment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentScriptTest {
    static String bash() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            var gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
            assertTrue(Files.isExecutable(gitBash), "Install Git Bash to run the deployment regression tests");
            return gitBash.toString();
        }
        return "/bin/bash";
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"invalid-sha", "missing-sha", "missing-secret", "weak-secret",
            "locked", "fetch-failure", "unknown-sha", "head-mismatch", "db-stopped",
            "maven-failure", "missing-jar", "config-failure", "docker-failure", "image-mismatch",
            "success", "legacy-success", "first-success", "health-retry", "health-failure",
            "startup-failure", "rollback-start-failure", "rollback-health-failure", "first-health-failure"})
    void executesDeploymentAgainstOnlyMockCommands(String scenario) throws Exception {
        var output = Files.createTempFile("deployment-test-", ".log");
        try {
            var process = new ProcessBuilder(bash(), "scripts/tests/deploy-test.sh", "scripts/deploy.sh", scenario)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "Mock deployment must have a finite timeout: " + scenario);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception e) { return e.toString(); }
            });
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
