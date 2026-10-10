package com.example.demo.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentWorkflowTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String path) throws Exception {
        try (var input = Files.newInputStream(Path.of(path))) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> job() throws Exception {
        return map(map(yaml(".github/workflows/deploy.yml").get("jobs")).get("deploy"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps() throws Exception {
        return (List<Map<String, Object>>) job().get("steps");
    }

    @Test
    void onlyCompletedCiTriggersAndPermissionIsReadOnly() throws Exception {
        var workflow = yaml(".github/workflows/deploy.yml");
        // SnakeYAML's YAML 1.1 resolver treats unquoted "on" as Boolean.TRUE.
        var on = map(workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE));
        assertEquals(Map.of("workflow_run", Map.of("workflows", List.of("Spring Boot CI"),
                "types", List.of("completed"))), on);
        assertEquals(Map.of("contents", "read"), workflow.get("permissions"));
        assertFalse(job().containsKey("permissions"), "The job must not grant extra permissions");
        var ci = yaml(".github/workflows/ci.yml");
        var ciJob = map(map(ci.get("jobs")).get("test"));
        @SuppressWarnings("unchecked")
        var ciSteps = (List<Map<String, Object>>) ciJob.get("steps");
        var qualityGate = ciSteps.stream()
                .filter(step -> "./mvnw verify".equals(step.get("run"))).findFirst().orElseThrow();
        assertFalse(qualityGate.containsKey("if"), "The quality gate must run unconditionally");
        assertFalse(qualityGate.containsKey("continue-on-error"), "IT failures must fail CI");
        assertFalse(ciJob.containsKey("continue-on-error"), "The test job must propagate failures");
        assertEquals(10, ciJob.get("timeout-minutes"));
    }

    static Stream<Arguments> triggerEvents() {
        return Stream.of("success", "failure").flatMap(conclusion ->
                Stream.of("push", "pull_request").flatMap(event ->
                        Stream.of("main", "feature").map(branch -> Arguments.of(conclusion, event, branch,
                                conclusion.equals("success") && event.equals("push") && branch.equals("main")))));
    }

    @ParameterizedTest
    @MethodSource("triggerEvents")
    void jobGuardRejectsPrFailureAndOtherBranches(String conclusion, String event, String branch,
                                                 boolean expected) throws Exception {
        String expression = (String) job().get("if");
        assertTrue(expression.startsWith("${{") && expression.trim().endsWith("}}"));
        String[] terms = expression.substring(3, expression.lastIndexOf("}}")).trim().split("\\s*&&\\s*");
        assertEquals(3, terms.length);
        var values = Map.of("conclusion", conclusion, "event", event, "head_branch", branch);
        var required = Map.of("conclusion", "success", "event", "push", "head_branch", "main");
        var pattern = Pattern.compile("github\\.event\\.workflow_run\\.(conclusion|event|head_branch)\\s*==\\s*'([^']+)'");
        var seen = new java.util.HashSet<String>();
        boolean allowed = true;
        for (String term : terms) {
            var match = pattern.matcher(term.trim());
            assertTrue(match.matches(), "Unsupported/unsafe job guard: " + term);
            assertTrue(seen.add(match.group(1)), "Duplicate guard leaves another event field unchecked");
            assertEquals(required.get(match.group(1)), match.group(2));
            allowed &= values.get(match.group(1)).equals(match.group(2));
        }
        assertEquals(required.keySet(), seen);
        assertEquals(expected, allowed);
    }

    @Test
    void verifiedShaSelectsBothRunnerScriptAndRemoteCommit() throws Exception {
        assertEquals("${{ github.event.workflow_run.head_sha }}", map(job().get("env")).get("DEPLOY_SHA"));
        var checkout = steps().stream().filter(s -> "actions/checkout@v4".equals(s.get("uses"))).findFirst().orElseThrow();
        assertEquals(Map.of("ref", "${{ github.event.workflow_run.head_sha }}", "persist-credentials", false),
                checkout.get("with"));
        var ssh = steps().stream().filter(s -> "appleboy/ssh-action@v1.2.0".equals(s.get("uses"))).findFirst().orElseThrow();
        var inputs = map(ssh.get("with"));
        assertEquals("DEPLOY_SHA,JWT_SECRET", inputs.get("envs"));
        assertEquals("scripts/deploy.sh", inputs.get("script_path"));
        assertFalse(inputs.containsKey("script"));
        assertFalse(inputs.containsKey("debug"));
        assertTrue(steps().indexOf(checkout) < steps().indexOf(ssh));
        assertEquals("Validate deployment inputs before SSH", steps().get(0).get("name"));
    }

    @Test
    void deploymentsSerializeWithoutCancellingTheRunningJob() throws Exception {
        assertEquals(Map.of("group", "production-deploy", "cancel-in-progress", false), job().get("concurrency"));
        assertEquals(40, job().get("timeout-minutes"));
    }

    @Test
    void shellContractPinsShaAndOnlyMutatesAppWithBoundedHealthAndRollback() throws Exception {
        String script = Files.readString(Path.of("scripts/deploy.sh"));
        for (String required : List.of("set -euo pipefail", "flock -n 9", "git fetch origin",
                "git cat-file -e \"${DEPLOY_SHA}^{commit}\"", "git checkout --detach \"$DEPLOY_SHA\"",
                "[[ \"$ACTUAL_SHA\" == \"$DEPLOY_SHA\" ]]", "./mvnw clean package -DskipTests",
                "org.opencontainers.image.revision=$DEPLOY_SHA", "inventory-management-system-app:$DEPLOY_SHA",
                "attempt<=30", "--connect-timeout 2 --max-time 5", "http://127.0.0.1:8080/v3/api-docs",
                "previous-image.yml", "&& health", "trap finish EXIT", "redact_logs")) {
            assertTrue(script.contains(required), "Missing safety contract: " + required);
        }
        assertFalse(Pattern.compile("(?m)^\\s*(?:git .*reset|.*compose.*\\bdown\\b|.*volume.*\\b(?:rm|prune)\\b|docker system prune|set -x)\\s*.*")
                .matcher(script).find());
        assertFalse(script.contains("origin/main"));
        assertFalse(script.contains("flyway undo"));
        assertEquals(2, script.split("up -d --no-deps --no-build --pull never --force-recreate app", -1).length - 1);
        assertTrue(Files.readString(Path.of(".gitignore")).contains(".deploy/"));
        assertTrue(Files.readString(Path.of(".gitattributes")).contains("*.sh text eol=lf"));
    }

    @Test
    void composeUsesShaTagsWithLocalDefaultAndContextExcludesServerSecrets() throws Exception {
        var compose = yaml("docker-compose.yml");
        var app = map(map(compose.get("services")).get("app"));
        assertEquals("inventory-management-system-app:${APP_IMAGE_TAG:-local}", app.get("image"));
        assertEquals(".", app.get("build"));
        assertTrue(map(compose.get("volumes")).containsKey("mariadb_data"));
        assertEquals(List.of("# The runtime image only needs the already-built jar.", "*", "!Dockerfile",
                "!target/", "target/*", "!target/demo-0.0.1-SNAPSHOT.jar"),
                Files.readAllLines(Path.of(".dockerignore")));
    }

    @ParameterizedTest(name = "runner input: {0}")
    @ValueSource(strings = {"valid", "EC2_HOST", "EC2_USER", "EC2_SSH_KEY", "JWT_SECRET", "invalid-sha", "weak-secret"})
    void runnerRejectsMissingSecretsBeforeAnySshStep(String scenario) throws Exception {
        var output = Files.createTempFile("deployment-input-", ".log");
        try {
            // Feed exact YAML text through stdin; Windows command-line quoting can alter bash -c scripts.
            var builder = new ProcessBuilder(DeploymentScriptTest.bash())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            for (String name : List.of("EC2_HOST", "EC2_USER", "EC2_SSH_KEY", "JWT_SECRET")) {
                builder.environment().put(name, "test-only-value-012345678901234567890");
            }
            builder.environment().put("DEPLOY_SHA", "a".repeat(40));
            if (scenario.startsWith("EC2_") || scenario.equals("JWT_SECRET")) builder.environment().put(scenario, "");
            if (scenario.equals("invalid-sha")) builder.environment().put("DEPLOY_SHA", "main");
            if (scenario.equals("weak-secret")) builder.environment().put("JWT_SECRET", "short");
            var process = builder.start();
            try (var input = process.getOutputStream()) {
                input.write(((String) steps().get(0).get("run")).getBytes(StandardCharsets.UTF_8));
            }
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed);
            assertEquals(scenario.equals("valid"), process.exitValue() == 0, Files.readString(output));
            String log = Files.readString(output);
            assertFalse(log.contains("test-only-value"));
            if (scenario.startsWith("EC2_") || scenario.equals("JWT_SECRET")) {
                assertTrue(log.contains(scenario + " is required"), log);
            } else if (scenario.equals("invalid-sha")) {
                assertTrue(log.contains("Invalid DEPLOY_SHA"), log);
            } else if (scenario.equals("weak-secret")) {
                assertTrue(log.contains("JWT_SECRET must be at least 32 bytes"), log);
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
