package com.example.demo.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ReviewedActionsTest {
    private static final Pattern PIN = Pattern.compile("([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)@([0-9a-f]{40})");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> inventory() {
        try {
            var actions = (List<Map<String, Object>>) load(Path.of("docs/audits/issue-82-actions.json")).get("actions");
            var result = new LinkedHashMap<String, Map<String, Object>>();
            for (var action : actions) {
                assertNull(result.put((String) action.get("repository"), action), "Duplicate reviewed publisher");
            }
            assertEquals(5, result.size());
            return result;
        } catch (Exception exception) {
            throw new AssertionError("Missing or invalid reviewed Action inventory", exception);
        }
    }

    static String reference(String repository) {
        var action = inventory().get(repository);
        assertNotNull(action, "Action requires publisher/release review: " + repository);
        return repository + "@" + action.get("commit_sha");
    }

    private static void assertReviewed(String reference) {
        var match = PIN.matcher(reference);
        assertTrue(match.matches(), "Action must use an explicit publisher and full lowercase commit SHA");
        assertEquals(reference(match.group(1)), reference, "Unreviewed Action commit");
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("uses")) references.add((String) map.get("uses"));
            map.values().forEach(child -> collectReferences(child, references));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectReferences(child, references));
        }
    }

    @Test
    void allWorkflowReferencesMatchReviewedOfficialReleasesAndVersionComments() throws Exception {
        var observed = new LinkedHashMap<String, Integer>();
        var publishers = new java.util.HashSet<String>();
        var reviewed = inventory();
        try (var files = Files.list(Path.of(".github/workflows"))) {
            for (var file : files.filter(path -> path.toString().matches(".*\\.ya?ml")).toList()) {
                var references = new ArrayList<String>();
                collectReferences(load(file), references);
                observed.put(file.getFileName().toString(), references.size());
                String source = Files.readString(file);
                assertEquals(references.size(), Pattern.compile(
                        "(?m)^\\s*(?:-\\s*)?uses: [a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+@[0-9a-f]{40} # v[0-9]+\\.[0-9]+\\.[0-9]+\\s*$")
                        .matcher(source).results().count(), "Every Action occurrence needs a SHA and version comment");
                for (String ref : references) {
                    assertReviewed(ref);
                    String publisher = ref.substring(0, ref.indexOf('@'));
                    publishers.add(publisher);
                    var action = reviewed.get(publisher);
                    String tag = (String) action.get("release_tag");
                    assertTrue(tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"));
                    assertTrue(Pattern.compile("(?m)^\\s*(?:-\\s*)?uses: " + Pattern.quote(ref)
                            + " # " + Pattern.quote(tag) + "\\s*$").matcher(source).find(),
                            "Missing reviewed version comment: " + ref);
                    assertEquals("https://github.com/" + publisher + "/releases/tag/" + tag,
                            action.get("release_url"));
                    assertEquals(false, action.get("release_draft"));
                    assertEquals(false, action.get("release_prerelease"));
                }
            }
        }
        assertEquals(Map.of("ci.yml", 13, "deploy.yml", 2), observed);
        assertEquals(reviewed.keySet(), publishers, "The inventory must cover every referenced publisher");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ciRetainsFiveMandatoryJobsReadOnlyPermissionsAndPrIsolation() throws Exception {
        var workflow = load(Path.of(".github/workflows/ci.yml"));
        assertEquals(Map.of("contents", "read"), workflow.get("permissions"));
        var on = (Map<?, ?>) (workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE));
        assertEquals(Set.of("push", "pull_request"), on.keySet());
        var jobs = (Map<String, Map<String, Object>>) workflow.get("jobs");
        assertEquals(Set.of("test", "terraform", "secret-scan", "java-static-analysis", "dependency-audit"), jobs.keySet());
        for (var job : jobs.values()) {
            if (job.containsKey("permissions")) assertEquals(Map.of("contents", "read"), job.get("permissions"));
            assertFalse(job.containsKey("continue-on-error"));
            assertFalse(job.containsKey("if"), "PR quality gates must run unconditionally");
            for (var step : (List<Map<String, Object>>) job.get("steps")) {
                assertFalse(step.containsKey("continue-on-error"));
                if (reference("actions/checkout").equals(step.get("uses"))) {
                    assertEquals(false, ((Map<?, ?>) step.get("with")).get("persist-credentials"));
                }
            }
        }
        String source = Files.readString(Path.of(".github/workflows/ci.yml"));
        assertFalse(Pattern.compile("\\$\\{\\{[^}]*\\bsecrets\\s*(?:\\.|\\[)").matcher(source).find(),
                "PR CI must not consume deployment secrets");
        assertFalse(source.contains("pull_request_target"));
        for (String command : List.of("./mvnw verify", "secret-self-test", "static-self-test",
                "quality_gates.py secrets", "quality_gates.py static", "quality_gates.py dependencies",
                "terraform test -no-color", "terraform init -backend=false -input=false -lockfile=readonly",
                "python3 scripts/docker/verify_image.py --scan")) {
            assertTrue(source.contains(command), "Missing mandatory gate: " + command);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "actions/checkout@v7", "actions/setup-java@v6", "actions/upload-artifact@latest",
            "hashicorp/setup-terraform@v4.0.1", "appleboy/ssh-action@v1.2.0",
            "actions/checkout@3d3c42e", "actions/checkout@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "unreviewed/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", "docker://alpine:latest"
    })
    void mutableShortUnknownCommitsAndUnreviewedPublishersFail(String reference) {
        assertThrows(AssertionError.class, () -> assertReviewed(reference));
    }
}
