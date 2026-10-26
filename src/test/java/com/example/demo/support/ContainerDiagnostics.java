package com.example.demo.support;

import org.junit.jupiter.api.extension.*;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Test-only, allowlisted evidence. Never emits messages, logs, commands, identity, PEM or inspect objects. */
public final class ContainerDiagnostics {
    private ContainerDiagnostics() { }
    public enum Kind { MARIADB, CADDY }
    public enum Phase { WAIT, STARTUP, TEST, CLI, EXPLICIT }
    public record Mark(Instant utc, long nanos) {
        public static Mark now() { return new Mark(Instant.now(), System.nanoTime()); }
        public long elapsed(long end) { return Math.max(0, end - nanos); }
    }
    private static final JsonMapper JSON = new JsonMapper();
    // One daemon, no queue: a blocked Docker API cannot accumulate diagnostic workers.
    private static final ExecutorService COLLECTOR = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { var t = new Thread(task, "container-evidence"); t.setDaemon(true); return t; });
    private static final Set<String> STATES = Set.of("created", "running", "paused", "restarting", "exited", "dead", "removing");
    private static final Set<String> EVIDENCE_KEYS = Set.of("javaVersion", "os", "osVersion", "testcontainersVersion",
            "dockerVersion", "containerState", "containerExit", "oomKilled", "certificates", "certificateProbeExit",
            "certificateProbeClass", "certificateSource", "clientErrorClass", "clockCliExit", "clock");
    private static final Set<String> NESTED_KEYS = Set.of("root", "intermediate", "leaf", "server", "notBeforeUtc",
            "notAfterUtc", "hostBeforeUtc", "hostAfterUtc", "containerUtc", "observationElapsedNanos", "bracketStatus",
            "guestMinusHostMinNanos", "guestMinusHostMaxNanos");
    private static final Set<String> EVIDENCE_VALUES = Set.of("UNKNOWN", "UNAVAILABLE", "WINDOWS", "LINUX", "MAC", "OTHER",
            "NONE", "TLS_NOT_YET_VALID", "TLS_EXPIRED", "TLS_VERIFY", "DIAGNOSTIC_SOCKET_PEER", "VALID", "WALL_CLOCK_REVERSED");
    private static boolean always() { return "always".equals(System.getProperty("test.container.diagnostics")); }

    public static void capture(GenericContainer<?> container, Kind kind, Phase phase, Mark start,
                               Throwable failure, Integer cliExit, String cliError) {
        record(kind, phase, start, failure, cliExit, cliError,
                out -> collect(container, kind, out), line -> System.err.println("CONTAINER_DIAGNOSTIC " + line));
    }

    /** Package-visible seams exercise diagnostic failure without swallowing the caller's original failure. */
    static void record(Kind kind, Phase phase, Mark start, Throwable failure, Integer cliExit, String cliError,
                       Consumer<Map<String, Object>> collector, Consumer<String> sink) {
        try {
            // Caller metadata never belongs to the collector. A cancelled worker may continue changing only evidence.
            var out = new TreeMap<String, Object>();
            var evidence = new ConcurrentHashMap<String, Object>();
            out.put("schema", 1); out.put("kind", kind.name()); out.put("phase", phase.name());
            out.put("startedUtc", start.utc().toString()); out.put("observedUtc", Instant.now().toString());
            out.put("elapsedNanos", start.elapsed(System.nanoTime()));
            out.putAll(classify(failure));
            if (failure == null && cliExit != null && cliExit != 0) out.put("failureClass", "CLI_NONZERO");
            out.put("waitClass", phase == Phase.WAIT ? "WAIT_FAILED" : phase == Phase.STARTUP ? "STARTUP_FAILED" : "NOT_APPLICABLE");
            if (cliExit != null) out.put("cliExit", cliExit);
            out.put("cliClass", cliClass(cliError));
            out.put("collectionStatus", "UNAVAILABLE");
            Future<?> pending = null;
            try {
                pending = COLLECTOR.submit(() -> collector.accept(evidence));
                pending.get(3, TimeUnit.SECONDS);
                out.put("collectionStatus", "COMPLETE");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); out.put("collectionStatus", "INTERRUPTED");
            } catch (TimeoutException e) {
                out.put("collectionStatus", "TIME_BUDGET");
            } catch (ExecutionException | RejectedExecutionException e) {
                out.put("collectionStatus", "FAILED_OR_BUSY");
            } finally {
                if (pending != null && !pending.isDone()) pending.cancel(true);
            }
            out.putAll(snapshot(evidence));
            sink.accept(JSON.writeValueAsString(out));
        } catch (Throwable diagnosticFailure) {
            // Do not attach a message/suppressed exception (it may contain credentials), or replace the original.
        }
    }

    private static Map<String, Object> snapshot(Map<String, Object> evidence) {
        var copy = new TreeMap<String, Object>();
        for (String key : EVIDENCE_KEYS) {
            Object value = evidence.get(key);
            if (value != null) {
                try { copy.put(key, frozen(value, 0)); }
                catch (RuntimeException unavailable) { copy.put(key, "UNAVAILABLE"); }
            }
        }
        return Map.copyOf(copy);
    }

    private static Object frozen(Object value, int depth) {
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) return value;
        if (value instanceof String text) {
            if (EVIDENCE_VALUES.contains(text) || STATES.contains(text) || !version(text).equals("UNKNOWN")) return text;
            if (text.length() <= 40) {
                try { if (Instant.parse(text).toString().equals(text)) return text; }
                catch (java.time.format.DateTimeParseException notAnInstant) { /* No free-form text. */ }
            }
            return "UNAVAILABLE";
        }
        if (value instanceof Map<?, ?> map && depth < 3) {
            var copy = new TreeMap<String, Object>(); int entries = 0;
            for (var entry : map.entrySet()) {
                if (++entries > 32) return "UNAVAILABLE";
                if (entry.getKey() instanceof String key && NESTED_KEYS.contains(key))
                    copy.put(key, frozen(entry.getValue(), depth + 1));
            }
            return Map.copyOf(copy);
        }
        return "UNAVAILABLE";
    }

    static Map<String, Object> classify(Throwable failure) {
        var classes = new ArrayList<String>(); var states = new TreeSet<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        if (failure != null) pending.add(failure);
        boolean notYet = false, expired = false;
        while (!pending.isEmpty() && seen.size() < 16) {
            var e = pending.remove(); if (!seen.add(e)) continue;
            String name = switch (e.getClass().getName()) {
                case "java.sql.SQLException", "java.sql.SQLNonTransientConnectionException",
                     "java.sql.SQLTransientConnectionException", "java.sql.SQLTimeoutException",
                     "javax.net.ssl.SSLHandshakeException", "java.security.cert.CertificateNotYetValidException",
                     "java.security.cert.CertificateExpiredException", "java.lang.AssertionError",
                     "java.lang.IllegalStateException", "java.util.concurrent.TimeoutException",
                     "org.testcontainers.containers.ContainerLaunchException" -> e.getClass().getName();
                default -> "OTHER";
            };
            classes.add(name);
            notYet |= e instanceof java.security.cert.CertificateNotYetValidException;
            expired |= e instanceof java.security.cert.CertificateExpiredException;
            if (e instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("(?:[0-9]{2}[0-9A-Z]{3}|HY[0-9]{3}|S1[0-9]{3})")) states.add(state);
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (e.getCause() != null) pending.add(e.getCause());
        }
        return Map.of("exceptionClasses", classes, "sqlStates", states,
                "failureClass", notYet ? "TLS_NOT_YET_VALID" : expired ? "TLS_EXPIRED" : failure == null ? "NONE" : "OTHER");
    }
    static String cliClass(String stderr) {
        if (stderr == null || stderr.isEmpty()) return "NONE";
        String lower = stderr.substring(0, Math.min(4096, stderr.length())).toLowerCase(Locale.ROOT);
        if (lower.contains("not yet valid")) return "TLS_NOT_YET_VALID";
        if (lower.contains("certificate has expired")) return "TLS_EXPIRED";
        if (lower.contains("certificate verify failed") || lower.contains("verify error")) return "TLS_VERIFY";
        return "OTHER";
    }
    static Map<String, Object> certificate(X509Certificate cert) {
        return Map.of("notBeforeUtc", cert.getNotBefore().toInstant().toString(),
                "notAfterUtc", cert.getNotAfter().toInstant().toString());
    }
    static Map<String, Object> clock(Instant before, Instant after, long elapsedNanos, String epoch) {
        if (!epoch.trim().matches("[0-9]{1,12}\\.[0-9]{1,9}")) throw new IllegalArgumentException("Unsupported clock format");
        var value = new BigDecimal(epoch.trim());
        long seconds = value.longValue();
        long nanos = value.remainder(BigDecimal.ONE).movePointRight(9).longValueExact();
        return clockValues(before, after, elapsedNanos, Instant.ofEpochSecond(seconds, nanos));
    }
    static Map<String, Object> clockValues(Instant before, Instant after, long elapsedNanos, Instant guest) {
        var out = new LinkedHashMap<String, Object>();
        out.put("hostBeforeUtc", before.toString()); out.put("hostAfterUtc", after.toString());
        out.put("containerUtc", guest.toString()); out.put("observationElapsedNanos", elapsedNanos);
        if (after.isBefore(before)) out.put("bracketStatus", "WALL_CLOCK_REVERSED");
        else {
            out.put("bracketStatus", "VALID");
            out.put("guestMinusHostMinNanos", Duration.between(after, guest).toNanos());
            out.put("guestMinusHostMaxNanos", Duration.between(before, guest).toNanos());
        }
        return out;
    }
    private static String version(String value) {
        return value != null && value.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:\\+[0-9]+)?") ? value : "UNKNOWN";
    }
    private static void collect(GenericContainer<?> container, Kind kind, Map<String, Object> out) {
        out.put("javaVersion", version(Runtime.version().toString()));
        String os = System.getProperty("os.name", "");
        out.put("os", os.startsWith("Windows") ? "WINDOWS" : os.equals("Linux") ? "LINUX" : os.equals("Mac OS X") ? "MAC" : "OTHER");
        out.put("osVersion", version(System.getProperty("os.version")));
        out.put("testcontainersVersion", version(GenericContainer.class.getPackage().getImplementationVersion()));
        try { out.put("dockerVersion", version(container.getDockerClient().versionCmd().exec().getVersion())); }
        catch (Exception e) { out.put("dockerVersion", "UNAVAILABLE"); }
        try {
            var state = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec().getState();
            out.put("containerState", STATES.contains(state.getStatus()) ? state.getStatus() : "UNKNOWN");
            if (state.getExitCodeLong() != null) out.put("containerExit", state.getExitCodeLong());
            out.put("oomKilled", Boolean.TRUE.equals(state.getOOMKilled()));
        } catch (Exception e) { out.put("containerState", "UNAVAILABLE"); }
        var certificates = new TreeMap<String, Object>();
        var paths = kind == Kind.MARIADB ? Map.of("server", "/var/lib/mysql/server-cert.pem")
                : Map.of("root", "/tmp/caddy-state/pki/authorities/local/root.crt",
                         "intermediate", "/tmp/caddy-state/pki/authorities/local/intermediate.crt",
                         "leaf", "/tmp/caddy-state/certificates/local/localhost/localhost.crt");
        paths.forEach((name, path) -> {
            try {
                var dates = container.copyFileFromContainer(path, input -> {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536) throw new IOException("Certificate size limit");
                    return certificate((X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(bytes)));
                });
                certificates.put(name, dates == null ? "UNAVAILABLE" : dates);
            } catch (Exception e) { certificates.put(name, "UNAVAILABLE"); }
            out.put("certificates", new TreeMap<>(certificates));
        });
        if (kind == Kind.MARIADB && "UNAVAILABLE".equals(certificates.get("server"))) {
            try {
                // Metadata only, no login. Keep verification errors and never call this an authenticated TLS success.
                var probe = container.execInContainer("sh", "-c", "timeout 2 openssl s_client -starttls mysql -unix /run/mysqld/mysqld.sock -showcerts -verify_return_error </dev/null");
                out.put("certificateProbeExit", probe.getExitCode());
                out.put("certificateProbeClass", cliClass(probe.getStderr()));
                String pem = probe.getStdout();
                if (pem.length() > 65536) throw new IOException("Certificate size limit");
                int begin = pem.indexOf("-----BEGIN CERTIFICATE-----"), end = pem.indexOf("-----END CERTIFICATE-----");
                if (begin >= 0 && end > begin) {
                    var cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(
                            pem.substring(begin, end + "-----END CERTIFICATE-----".length()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                    certificates.put("server", certificate(cert));
                    out.put("certificates", new TreeMap<>(certificates));
                    out.put("certificateSource", "DIAGNOSTIC_SOCKET_PEER");
                }
            } catch (Exception e) { out.put("certificateSource", "UNAVAILABLE"); }
        }
        if (kind == Kind.CADDY) {
            try {
                String stderr = container.copyFileFromContainer("/tmp/client-diagnostic", input ->
                        new String(input.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8));
                out.put("clientErrorClass", cliClass(stderr));
            } catch (Exception e) { out.put("clientErrorClass", "UNAVAILABLE"); }
        }
        try {
            var start = Mark.now();
            var result = container.execInContainer("date", "-u", "+%s.%N");
            out.put("clockCliExit", result.getExitCode());
            if (result.getExitCode() == 0) out.put("clock", clock(start.utc(), Instant.now(), start.elapsed(System.nanoTime()), result.getStdout()));
        } catch (Exception e) { out.put("clock", "UNAVAILABLE"); }
    }

    public static Container.ExecResult exec(GenericContainer<?> container, Kind kind, String... command)
            throws IOException, InterruptedException {
        var start = Mark.now();
        try {
            var result = container.execInContainer(command);
            if (result.getExitCode() != 0 || always())
                capture(container, kind, Phase.CLI, start, null, result.getExitCode(), result.getStderr());
            return result;
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            capture(container, kind, Phase.CLI, start, failure, null, null);
            throw failure;
        }
    }

    public static final class OnFailure implements BeforeAllCallback, BeforeEachCallback, BeforeTestExecutionCallback, TestExecutionExceptionHandler,
            LifecycleMethodExecutionExceptionHandler {
        private final Supplier<GenericContainer<?>> target;
        private final Kind kind;
        private final ExtensionContext.Namespace namespace = ExtensionContext.Namespace.create(OnFailure.class, this);
        public OnFailure(Supplier<GenericContainer<?>> target, Kind kind) { this.target = target; this.kind = kind; }
        @Override public void beforeAll(ExtensionContext context) { mark(context); }
        @Override public void beforeEach(ExtensionContext context) { mark(context); }
        @Override public void beforeTestExecution(ExtensionContext context) { mark(context); }
        private void mark(ExtensionContext context) { context.getStore(namespace).put(Mark.class, Mark.now()); }
        @Override public void handleTestExecutionException(ExtensionContext c, Throwable failure) throws Throwable { handle(c, failure); }
        @Override public void handleBeforeAllMethodExecutionException(ExtensionContext c, Throwable failure) throws Throwable { handle(c, failure); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext c, Throwable failure) throws Throwable { handle(c, failure); }
        private void handle(ExtensionContext context, Throwable failure) throws Throwable {
            try {
                var start = context == null ? null : context.getStore(namespace).get(Mark.class, Mark.class);
                capture(target.get(), kind, Phase.TEST, start == null ? Mark.now() : start, failure, null, null);
            }
            catch (Throwable unavailable) { /* Diagnostic acquisition cannot replace the failure. */ }
            throw failure;
        }
    }

    public static final class MariaDb extends MariaDBContainer {
        private Mark start = Mark.now(); private boolean ready, reported;
        public MariaDb(String image) { super(image); }
        @Override protected void containerIsStarting(com.github.dockerjava.api.command.InspectContainerResponse info) {
            start = Mark.now(); ready = false; reported = false; super.containerIsStarting(info);
        }
        @Override protected void waitUntilContainerStarted() {
            try { super.waitUntilContainerStarted(); }
            catch (RuntimeException | Error failure) { capture(this, Kind.MARIADB, Phase.WAIT, start, failure, null, null); reported = true; throw failure; }
        }
        @Override protected void containerIsStarted(com.github.dockerjava.api.command.InspectContainerResponse info) {
            super.containerIsStarted(info); ready = true;
            if (always()) capture(this, Kind.MARIADB, Phase.EXPLICIT, start, null, null, null);
        }
        @Override protected void containerIsStopping(com.github.dockerjava.api.command.InspectContainerResponse info) {
            if (!ready && !reported) capture(this, Kind.MARIADB, Phase.STARTUP, start, null, null, null);
            super.containerIsStopping(info);
        }
    }
    public static class Caddy extends GenericContainer<Caddy> {
        private Mark start = Mark.now(); private boolean ready, reported;
        public Caddy(String image) { super(DockerImageName.parse(image)); }
        @Override protected void containerIsStarting(com.github.dockerjava.api.command.InspectContainerResponse info) {
            start = Mark.now(); ready = false; reported = false; super.containerIsStarting(info);
        }
        @Override protected void waitUntilContainerStarted() {
            try { super.waitUntilContainerStarted(); }
            catch (RuntimeException | Error failure) { capture(this, Kind.CADDY, Phase.WAIT, start, failure, null, null); reported = true; throw failure; }
        }
        @Override protected void containerIsStarted(com.github.dockerjava.api.command.InspectContainerResponse info) {
            super.containerIsStarted(info); ready = true;
            if (always()) capture(this, Kind.CADDY, Phase.EXPLICIT, start, null, null, null);
        }
        @Override protected void containerIsStopping(com.github.dockerjava.api.command.InspectContainerResponse info) {
            if (!ready && !reported) capture(this, Kind.CADDY, Phase.STARTUP, start, null, null, null);
            super.containerIsStopping(info);
        }
    }
}
