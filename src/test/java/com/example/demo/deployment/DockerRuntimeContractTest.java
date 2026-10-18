package com.example.demo.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DockerRuntimeContractTest {
    @Test
    void databaseWaitsFitTransactionAndGracefulShutdownFitsComposeCleanupBudget() throws Exception {
        var properties=new Properties();
        try(var reader=Files.newBufferedReader(Path.of("src/main/resources/application-prod.properties"))) { properties.load(reader); }
        long validation=Long.parseLong(properties.getProperty("spring.datasource.hikari.validation-timeout"));
        long acquire=Long.parseLong(properties.getProperty("spring.datasource.hikari.connection-timeout"));
        long transaction=Duration.parse("PT"+properties.getProperty("spring.transaction.default-timeout").toUpperCase(java.util.Locale.ROOT)).toMillis();
        long drain=Duration.parse("PT"+properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase").toUpperCase(java.util.Locale.ROOT)).toMillis();
        long stop=Duration.parse("PT"+app().get("stop_grace_period").toString().toUpperCase(java.util.Locale.ROOT)).toMillis();
        long connect=Long.parseLong(properties.getProperty("spring.datasource.hikari.data-source-properties.connectTimeout"));
        long socket=Long.parseLong(properties.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"));
        long lock=Long.parseLong(properties.getProperty("spring.datasource.hikari.connection-init-sql").split("=")[1])*1000;
        assertEquals("10",properties.getProperty("spring.datasource.hikari.maximum-pool-size"),"Keep existing pool capacity");
        assertTrue(validation<acquire && acquire<transaction && lock<transaction);
        assertTrue(connect<acquire && transaction<socket && socket<=drain);
        assertTrue(transaction<drain && drain+5000<=stop,"The Docker stop budget must preserve HTTP drain and cleanup time");
        assertEquals("graceful",properties.getProperty("server.shutdown"));
    }
    private static String dockerfile() throws Exception {
        return Files.readString(Path.of("Dockerfile"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> app() throws Exception {
        try (var input = Files.newInputStream(Path.of("docker-compose.yml"))) {
            Map<String, Object> compose = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return (Map<String, Object>) ((Map<?, ?>) compose.get("services")).get("app");
        }
    }

    @Test
    void java17BuildAndJreRuntimeUseReviewedOfficialDigestPins() throws Exception {
        String file = dockerfile();
        var stages = Pattern.compile("(?m)^FROM eclipse-temurin:17-(jdk|jre)-noble@sha256:([0-9a-f]{64}) AS (build|runtime)$")
                .matcher(file).results().toList();
        assertEquals(2, stages.size());
        assertEquals("jdk", stages.get(0).group(1));
        assertEquals("build", stages.get(0).group(3));
        assertEquals("jre", stages.get(1).group(1));
        assertEquals("runtime", stages.get(1).group(3));
        assertTrue(file.contains("COPY --from=build --chown=0:0 --chmod=0444"));
    }

    @Test
    void fixedUidOwnsNeitherApplicationNorJarAndRequiresOnlyTemporaryWrites() throws Exception {
        String file = dockerfile();
        assertTrue(file.contains("groupadd --gid 10001"));
        assertTrue(file.contains("useradd --uid 10001 --gid 10001 --no-create-home"));
        assertTrue(file.contains("install -d -o root -g root -m 0755 /app"));
        assertTrue(file.contains("USER 10001:10001"));
        assertTrue(file.contains("ENTRYPOINT [\"java\",\"-jar\",\"/app/app.jar\"]"));
        var app = app();
        assertEquals("10001:10001", app.get("user"));
        assertEquals(true, app.get("read_only"));
        assertEquals(List.of("ALL"), app.get("cap_drop"));
        assertEquals(List.of("no-new-privileges:true"), app.get("security_opt"));
        assertEquals(List.of("/tmp:rw,noexec,nosuid,size=32m,mode=1777"), app.get("tmpfs"));
    }

    @Test
    void memoryBudgetIsExplicitAndHeapDoesNotConsumeAllContainerMemory() throws Exception {
        var app = app();
        String budget = "${APP_MEMORY_LIMIT:?APP_MEMORY_LIMIT must be selected for the verified host}";
        assertEquals(budget, app.get("mem_limit"));
        assertEquals(budget, app.get("memswap_limit"));
        assertEquals(256, app.get("pids_limit"));
        String file = dockerfile();
        for (String flag : List.of("InitialRAMPercentage=10", "MaxRAMPercentage=25", "MaxMetaspaceSize=160m",
                "MaxDirectMemorySize=32m", "ReservedCodeCacheSize=64m", "-Xss512k", "ExitOnOutOfMemoryError")) {
            assertTrue(file.contains(flag), "Missing JVM memory boundary: " + flag);
        }
        assertFalse(file.contains("HeapDumpOnOutOfMemoryError"), "Heap dumps may contain credentials");
    }

    @Test
    void sourceBuildUsesDependencyCacheAndCommitTimestampWithoutHostJar() throws Exception {
        String file = dockerfile();
        assertTrue(file.indexOf("COPY mvnw pom.xml") < file.indexOf("dependency:go-offline"));
        assertTrue(file.indexOf("dependency:go-offline") < file.indexOf("COPY src/main/"));
        assertEquals(2, file.split("target=/root/.m2,sharing=locked", -1).length - 1);
        assertTrue(file.contains("project.build.outputTimestamp=${SOURCE_DATE_EPOCH}"));
        assertFalse(Pattern.compile("(?m)^COPY target/").matcher(file).find());
        String ignore = Files.readString(Path.of(".dockerignore"));
        assertTrue(ignore.lines().anyMatch("*"::equals));
        for (String excluded : List.of("!target", "!.git/", "!src/test", "!terraform", "!.env")) {
            assertFalse(ignore.contains(excluded), "Unexpected build input: " + excluded);
        }
        assertTrue(ignore.contains("**/settings.xml"));
    }

    @Test
    void deployBuildsOnceInDockerBeforeAppOnlyCutoverAndPinsRevision() throws Exception {
        String script = Files.readString(Path.of("scripts/deploy.sh"));
        assertFalse(script.contains("./mvnw clean package"));
        assertFalse(script.contains("target/demo-0.0.1-SNAPSHOT.jar"));
        assertTrue(script.contains("git show -s --format=%ct \"$DEPLOY_SHA\""));
        assertTrue(script.contains("--build-arg \"REVISION=$DEPLOY_SHA\""));
        assertTrue(script.contains("--build-arg \"SOURCE_DATE_EPOCH=$source_epoch\""));
        assertTrue(script.indexOf("docker build ") < script.indexOf("cutover=true"));
        assertEquals(2, script.split("up -d --no-deps --no-build --pull never --force-recreate app", -1).length - 1);
        assertTrue(script.contains("previous-image.yml"));
    }

    @Test
    void imageHealthUsesTheSameReadinessContractAndDoesNotExposeDetails() throws Exception {
        String file = dockerfile();
        assertTrue(file.contains("inventory-management-system.health-path=\"/readyz\""));
        assertTrue(file.contains("org.opencontainers.image.revision=\"${REVISION}\""));
        assertTrue(file.contains("HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3"));
        assertTrue(file.contains("curl --fail --silent --max-time 2 http://127.0.0.1:8080/readyz >/dev/null"));
        assertFalse(file.contains("8081"));
    }
}
