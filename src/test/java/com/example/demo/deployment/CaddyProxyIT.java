package com.example.demo.deployment;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Official binary, network-none Docker container, local CA only; never contacts a public CA. */
class CaddyProxyIT {
    private static final String SHA256 = "727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b";
    private static final String REQUEST_ID = "08faf7bc-51a4-4c29-b22c-5cfcf0026946";
    private static Path work;
    private static GenericContainer<?> proxy;

    @BeforeAll
    static void startProxyWithoutPublicCaAccess() throws Exception {
        work = Files.createTempDirectory("caddy-proxy-it-");
        // The same reviewed official archive used by the host installer. No executable use before digest verification.
        var download = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build().send(HttpRequest.newBuilder(URI.create(
                        "https://github.com/caddyserver/caddy/releases/download/v2.11.7/caddy_2.11.7_linux_amd64.tar.gz"))
                        .timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals(SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(download.body())));
        Files.write(work.resolve("caddy.tar.gz"), download.body());
        String production = Files.readString(Path.of("terraform/caddy/Caddyfile.tmpl")).replace("__APP_DOMAIN__", "api.example.com");
        Files.writeString(work.resolve("production.Caddyfile"), production);
        // Only test domain, port, state/admin and issuer differ; production routing/HSTS/log policy are reused.
        String local = production.replace("api.example.com", "localhost").replace("admin 127.0.0.1:2019", "admin off\n\thttp_port 8081\n\thttps_port 8443\n\tskip_install_trust")
                .replace("/var/lib/caddy", "/tmp/caddy-state").replace("localhost {", "localhost {\n\ttls internal");
        local += "\nhttp://:8080 {\n"
                + "  header X-Request-Id " + REQUEST_ID + "\n"
                + "  header Strict-Transport-Security \"max-age=31536000; includeSubDomains\"\n"
                + "  handle /forward {\n    respond \"{http.request.header.X-Forwarded-Proto}|{http.request.header.X-Forwarded-Host}|{http.request.header.X-Forwarded-For}|{http.request.header.Forwarded}|{http.request.header.X-Forwarded-Port}|{http.request.header.X-Forwarded-Prefix}|{http.request.header.X-Forwarded-Ssl}\"\n  }\n"
                + "  handle /conflict {\n    respond `{\"status\":409}` 409\n  }\n"
                + "  handle /protected {\n    respond `{\"status\":401}` 401\n  }\n"
                + "  handle /failure {\n    reverse_proxy 127.0.0.1:9\n  }\n"
                + "  handle {\n    respond `{\"status\":\"UP\"}`\n  }\n}\n";
        Files.writeString(work.resolve("local.Caddyfile"), local);
        // Reuse the existing MariaDB test image as a Linux tool environment; no database is started.
        proxy = new GenericContainer<>("mariadb:12.2").withNetworkMode("none")
                .withCopyFileToContainer(MountableFile.forHostPath(work.resolve("caddy.tar.gz")), "/tmp/caddy.tar.gz")
                .withCopyFileToContainer(MountableFile.forHostPath(work.resolve("production.Caddyfile")), "/tmp/production.Caddyfile")
                .withCopyFileToContainer(MountableFile.forHostPath(work.resolve("local.Caddyfile")), "/tmp/local.Caddyfile")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/bash"))
                .withCommand("-ec", "tar -xzf /tmp/caddy.tar.gz -C /usr/local/bin caddy; test \"$(caddy version | cut -d' ' -f1)\" = v2.11.7; caddy validate --config /tmp/production.Caddyfile --adapter caddyfile; exec caddy run --config /tmp/local.Caddyfile --adapter caddyfile")
                .waitingFor(Wait.forLogMessage(".*certificate obtained successfully.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        proxy.start();
        // Clients execute inside the network-none container and trust only its disposable local CA.
    }

    private record Response(int statusCode, String body, String rawHeaders) {
        String header(String name) {
            return rawHeaders.lines().filter(line -> line.toLowerCase().startsWith(name.toLowerCase() + ":"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElseThrow();
        }
    }

    private Response request(boolean tls, String path, String headers, String body) throws Exception {
        String wire = (body.isEmpty() ? "GET " : "POST ") + path + " HTTP/1.1\r\nHost: localhost:"
                + (tls ? 8443 : 8081) + "\r\nConnection: close\r\n" + headers
                + (body.isEmpty() ? "" : "Content-Length: " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\r\n")
                + "\r\n" + body;
        String encoded = java.util.Base64.getEncoder().encodeToString(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String command = tls
                ? "printf '%s' \"$1\" | base64 --decode | timeout 10 openssl s_client -quiet -connect 127.0.0.1:8443 -servername localhost -CAfile /tmp/caddy-state/pki/authorities/local/root.crt -verify_return_error -verify_hostname localhost 2>/tmp/client-diagnostic"
                : "exec 3<>/dev/tcp/127.0.0.1/8081; printf '%s' \"$1\" | base64 --decode >&3; timeout 10 cat <&3";
        var result = proxy.execInContainer("/bin/bash", "-ec", command, "client", encoded);
        assertEquals(0, result.getExitCode(), "Local TLS client must verify certificate and hostname");
        String response = result.getStdout();
        int split = response.indexOf("\r\n\r\n");
        assertTrue(split > 0);
        return new Response(Integer.parseInt(response.split(" ", 3)[1]), response.substring(split + 4), response.substring(0, split));
    }

    private Response get(String path) throws Exception {
        return request(true, path, "", "");
    }

    @Test
    void httpRedirectsToHttpsInsteadOfServingApi() throws Exception {
        var response = request(false, "/readyz", "", "");
        assertEquals(308, response.statusCode());
        assertEquals("https://localhost/readyz", response.header("Location"));
        assertNotEquals("{\"status\":\"UP\"}", response.body());
    }

    @Test
    void httpsPreservesStatusBodyRequestIdAndAddsOnlyMaxAgeHsts() throws Exception {
        var response = get("/readyz");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertEquals(REQUEST_ID, response.header("X-Request-Id"));
        assertEquals("max-age=31536000", response.header("Strict-Transport-Security"));
        assertEquals(409, get("/conflict").statusCode());
        assertEquals("{\"status\":409}", get("/conflict").body());
        assertEquals(401, get("/protected").statusCode());
    }

    @Test
    void untrustedClientCannotSpoofForwardedHeaders() throws Exception {
        var response = request(true, "/forward", "X-Forwarded-Proto: http\r\nX-Forwarded-Host: attacker.example\r\n"
                + "Forwarded: for=198.51.100.123;proto=http;host=attacker.example\r\nX-Forwarded-Port: 1234\r\n"
                + "X-Forwarded-Prefix: /spoofed\r\nX-Forwarded-Ssl: on\r\nX-Forwarded-For: 198.51.100.123\r\n", "");
        assertEquals(200, response.statusCode());
        assertEquals("https|localhost:8443|127.0.0.1||||", response.body());
    }

    @Test
    void actualProxyOutputNeverContainsRequestSecretsEvenOnUpstreamFailure() throws Exception {
        List<String> canaries = List.of("jwt-secret-canary", "cookie-secret-canary", "idempotency-secret-key",
                "query-secret-canary", "body-secret-canary");
        for (String path : List.of("/readyz", "/failure")) {
            var response = request(true, path + "?secret=" + canaries.get(3), "Authorization: Bearer " + canaries.get(0)
                    + "\r\nCookie: session=" + canaries.get(1) + "\r\nIdempotency-Key: " + canaries.get(2) + "\r\n", canaries.get(4));
            assertEquals(path.equals("/failure") ? 502 : 200, response.statusCode());
        }
        String actualLogs = proxy.getLogs();
        for (String canary : canaries) assertFalse(actualLogs.contains(canary), "Secret appeared in actual proxy logs");
        assertFalse(actualLogs.contains("acme-v02.api.letsencrypt.org"));
        assertFalse(actualLogs.contains("acme.zerossl.com"));
    }

    @AfterAll
    static void stopProxy() throws Exception {
        if (proxy != null) proxy.close();
        if (work != null) {
            try (var files = Files.walk(work)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
