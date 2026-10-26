package com.example.demo.support;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.system.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class ContainerDiagnosticsRobustnessTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String CANARY = "SYNTHETIC-private-password-JWT-not-for-output";
    private static void readyWorker() {
        var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(ContainerDiagnostics.class, "COLLECTOR");
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.getActiveCount() == 0);
    }
    @BeforeEach void idleBefore() { readyWorker(); }
    @AfterEach void idleAfter() { Thread.interrupted(); readyWorker(); }
    private static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            for (;;) {
                try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture signal missing"); return; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static ExtensionContext context() {
        var context = mock(ExtensionContext.class);
        var store = mock(ExtensionContext.Store.class);
        var values = new ConcurrentHashMap<Object, Object>();
        when(context.getStore(any())).thenReturn(store);
        doAnswer(i -> { values.put(i.getArgument(0), i.getArgument(1)); return null; }).when(store).put(any(), any());
        when(store.get(any(), any())).thenAnswer(i -> values.get(i.getArgument(0)));
        return context;
    }

    @Test void parallelContextsKeepTheirOwnStart(CapturedOutput output) throws Throwable {
        var container = mock(GenericContainer.class);
        var observer = new ContainerDiagnostics.OnFailure(() -> container, ContainerDiagnostics.Kind.CADDY);
        var a = context(); var b = context();
        var markA = new ContainerDiagnostics.Mark(Instant.parse("2026-10-26T01:00:00Z"), System.nanoTime());
        var markB = new ContainerDiagnostics.Mark(Instant.parse("2026-10-26T02:00:00Z"), System.nanoTime());
        var original = new AssertionError(CANARY);
        try (var marks = mockStatic(ContainerDiagnostics.Mark.class)) {
            marks.when(ContainerDiagnostics.Mark::now).thenReturn(markA, markB);
            observer.beforeTestExecution(a);
            observer.beforeTestExecution(b); // Deterministic interleaving of two concurrent test contexts.
            assertSame(original, assertThrows(AssertionError.class, () -> observer.handleTestExecutionException(a, original)));
        }
        var line = output.getErr().lines().filter(s -> s.startsWith("CONTAINER_DIAGNOSTIC ")).reduce((x,y) -> y).orElseThrow();
        var record = JSON.readTree(line.substring("CONTAINER_DIAGNOSTIC ".length()));
        assertEquals(markA.utc().toString(), record.path("startedUtc").asText(),
                "A must not use B's start time");
        assertFalse(line.contains(CANARY));
    }

    @Test void collectorCannotPublishUnknownOrSensitiveValues() {
        var line = new AtomicReference<String>();
        ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST,
                ContainerDiagnostics.Mark.now(), new AssertionError(CANARY), null, null, map -> {
                    map.put("unapprovedSecret", CANARY);
                    map.put("javaVersion", CANARY);
                    map.put("clock", Map.of("rawJwt", CANARY, "hostBeforeUtc", CANARY));
                    map.put("collectionStatus", "COMPLETE");
                    map.put("schema", 999); map.put("elapsedNanos", -1L);
                }, line::set);
        assertNotNull(line.get());
        assertFalse(line.get().contains(CANARY), "Unapproved collector values escaped the evidence boundary");
        assertFalse(line.get().contains("unapprovedSecret"));
        assertEquals(1, JSON.readTree(line.get()).path("schema").asInt());
        assertTrue(JSON.readTree(line.get()).path("elapsedNanos").asLong() >= 0);
    }

    @Test void lateNestedMutationCannotDropTheDiagnosticRecord() {
        var serializing = new CountDownLatch(1); var mutated = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var nested = new TreeMap<String, Object>() {
            @Override public Set<Map.Entry<String, Object>> entrySet() {
                var entries = super.entrySet();
                return new AbstractSet<>() {
                    @Override public int size() { return entries.size(); }
                    @Override public Iterator<Map.Entry<String,Object>> iterator() {
                        var delegate = entries.iterator();
                        return new Iterator<>() {
                            boolean first = true;
                            @Override public boolean hasNext() { return delegate.hasNext(); }
                            @Override public Map.Entry<String,Object> next() {
                                var entry = delegate.next();
                                if (first) { first = false; serializing.countDown(); uninterruptibly(mutated); }
                                return entry;
                            }
                        };
                    }
                };
            }
        };
        nested.put("hostBeforeUtc", "2026-10-26T01:00:00Z");
        nested.put("hostAfterUtc", "2026-10-26T01:00:01Z");
        var line = new AtomicReference<String>();
        try {
            ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST,
                    ContainerDiagnostics.Mark.now(), new AssertionError("original fixture failure"), null, null, map -> {
                        try {
                            map.put("clock", nested);
                            uninterruptibly(serializing); // Ignore cancellation just like uninterruptible Docker I/O.
                            nested.put("containerUtc", "2026-10-26T01:00:02Z");
                            mutated.countDown();
                        } finally { finished.countDown(); }
                    }, line::set);
            assertNotNull(line.get(), "A mutable nested map must not silently discard the whole record");
            assertEquals("TIME_BUDGET", JSON.readTree(line.get()).path("collectionStatus").asText());
        } finally {
            serializing.countDown(); mutated.countDown(); uninterruptibly(finished);
        }
    }

    @Test void uncancellableCollectorMakesLaterEvidenceBusyButKeepsOriginal(CapturedOutput output) {
        var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var container = mock(GenericContainer.class, RETURNS_DEEP_STUBS);
        when(container.getDockerClient().versionCmd().exec().getVersion()).thenAnswer(i -> {
            try { uninterruptibly(release); return "29.8.1"; } finally { finished.countDown(); }
        });
        var observer = new ContainerDiagnostics.OnFailure(() -> container, ContainerDiagnostics.Kind.CADDY);
        var original = new AssertionError(CANARY);
        var start = ContainerDiagnostics.Mark.now();
        try {
            assertSame(original, assertThrows(AssertionError.class,
                    () -> observer.handleTestExecutionException(context(), original)));
            assertTrue(output.getErr().contains("\"collectionStatus\":\"TIME_BUDGET\""));
            var next = new AtomicReference<String>();
            ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST,
                    start, original, null, null, map -> fail("Busy worker must not run this collector"), next::set);
            assertEquals("FAILED_OR_BUSY", JSON.readTree(next.get()).path("collectionStatus").asText());
            assertFalse(next.get().contains(CANARY));
            assertEquals(0, original.getSuppressed().length);
        } finally { release.countDown(); uninterruptibly(finished); }
    }

    @Test void interruptionKeepsTheCallerInterruptFlagAndFailureMetadata() {
        var release = new CountDownLatch(1);
        var line = new AtomicReference<String>();
        try {
            Thread.currentThread().interrupt();
            ContainerDiagnostics.record(ContainerDiagnostics.Kind.MARIADB, ContainerDiagnostics.Phase.TEST,
                    ContainerDiagnostics.Mark.now(), new AssertionError(CANARY), null, null,
                    map -> uninterruptibly(release), line::set);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("INTERRUPTED", JSON.readTree(line.get()).path("collectionStatus").asText());
            assertFalse(line.get().contains(CANARY));
        } finally { Thread.interrupted(); release.countDown(); }
    }

    @Test void topLevelLateWritesCannotCorruptJson() {
        var release = new AtomicBoolean(false); var finished = new CountDownLatch(1);
        var line = new AtomicReference<String>();
        try {
            ContainerDiagnostics.record(ContainerDiagnostics.Kind.CADDY, ContainerDiagnostics.Phase.TEST,
                    ContainerDiagnostics.Mark.now(), new AssertionError(CANARY), null, null, map -> {
                        try {
                            while (!release.get()) {
                                map.put("containerState", "running"); map.remove("containerState");
                                if (Thread.currentThread().isInterrupted()) Thread.interrupted(); // Synthetic cancellation-resistant collector.
                            }
                        } finally { finished.countDown(); }
                    }, line::set);
            assertNotNull(line.get()); assertEquals("TIME_BUDGET", JSON.readTree(line.get()).path("collectionStatus").asText());
            assertFalse(line.get().contains(CANARY));
        } finally { release.set(true); uninterruptibly(finished); }
    }
}
