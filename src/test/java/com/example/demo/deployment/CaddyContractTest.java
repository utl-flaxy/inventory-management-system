package com.example.demo.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CaddyContractTest {
    private String read(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test
    void officialArchiveIsVerifiedAndVersionCheckedBeforeInstallation() throws Exception {
        String script = read("scripts/tls/install-caddy.sh");
        assertTrue(script.contains("readonly caddy_version=2.11.7"));
        assertTrue(script.contains("readonly caddy_sha256=727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b"));
        assertTrue(script.contains("https://github.com/caddyserver/caddy/releases/download/v$caddy_version/caddy_${caddy_version}_linux_amd64.tar.gz"));
        assertTrue(script.indexOf("sha256sum --check --status") < script.indexOf("tar -xzf"));
        assertTrue(script.indexOf("tar -xzf") < script.indexOf("\"$work/caddy\" version"));
        assertTrue(script.indexOf("\"$work/caddy\" version") < script.indexOf("install -o root -g root -m 0755"));
        assertFalse(script.contains("caddyserver.com/api/download"));
        assertFalse(script.contains("xcaddy"));
        assertFalse(script.contains("enable --now"));
        assertTrue(script.indexOf("caddy validate") < script.indexOf("systemctl daemon-reload"));
    }

    @Test
    void serviceUsesDedicatedIdentityPrivatePersistentStateAndMinimalCapabilities() throws Exception {
        String unit = read("terraform/caddy/caddy.service");
        for (String setting : List.of("User=caddy", "Group=caddy", "UMask=0077", "NoNewPrivileges=true",
                "AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE",
                "ProtectSystem=strict", "ProtectHome=true", "ReadWritePaths=/var/lib/caddy",
                "ExecStartPre=/usr/local/bin/caddy validate", "Restart=on-failure")) assertTrue(unit.contains(setting));
        assertTrue(unit.contains("caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile &&"));
        assertFalse(unit.contains("EnvironmentFile"));
        String script = read("scripts/tls/install-caddy.sh");
        assertTrue(script.contains("-o caddy -g caddy -m 0700 /var/lib/caddy"));
        assertTrue(script.contains("-o root -g caddy -m 0640"));
        assertTrue(script.contains("--shell /sbin/nologin caddy"));
    }

    @Test
    void proxyUsesAutomaticHttpsLoopbackAdminHstsAndNoRequestLogging() throws Exception {
        String config = read("terraform/caddy/Caddyfile.tmpl");
        assertTrue(config.contains("admin 127.0.0.1:2019"));
        assertTrue(config.contains("storage file_system /var/lib/caddy"));
        assertTrue(config.contains("__APP_DOMAIN__ {"));
        assertTrue(config.contains("reverse_proxy 127.0.0.1:8080"));
        assertTrue(config.contains("header >Strict-Transport-Security \"max-age=31536000\""));
        for (String header : List.of("Forwarded", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Ssl"))
            assertTrue(config.contains("header_up -" + header));
        assertTrue(config.contains("exclude http.log.error"));
        for (String forbidden : List.of("includeSubDomains", "preload", "auto_https off", "debug", "log_credentials",
                "header_down X-Request-Id", "tls internal", "Authorization", "Cookie", "Idempotency-Key"))
            assertFalse(config.contains(forbidden), forbidden);
        assertEquals(1, java.util.regex.Pattern.compile("(?m)^\\s*log\\s+").matcher(config).results().count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyAppPortChangesAndDeploymentRemainsIndependentOfCaddy() throws Exception {
        Map<String, Object> compose = new Yaml().load(read("docker-compose.yml"));
        var services = (Map<String, Map<String, Object>>) compose.get("services");
        assertEquals(List.of("127.0.0.1:8080:8080"), services.get("app").get("ports"));
        assertFalse(services.containsKey("caddy"));
        String deploy = read("scripts/deploy.sh");
        assertTrue(deploy.contains("http://127.0.0.1:8080"));
        assertFalse(deploy.toLowerCase().contains("caddy"));
        assertFalse(read(".github/workflows/deploy.yml").toLowerCase().contains("caddy"));
        String main = read("terraform/main.tf");
        assertTrue(main.contains("\"__APP_DOMAIN__\", var.app_domain"));
        assertTrue(main.contains("filebase64(\"${path.module}/caddy/Caddyfile.tmpl\")"));
        assertTrue(read("terraform/outputs.tf").contains("value       = aws_eip.app.public_ip"));
        assertTrue(read("terraform/variables.tf").contains("variable \"app_domain\""));
    }

    @ParameterizedTest(name = "enable {0}")
    @ValueSource(strings = {"success", "bad-domain", "ip-domain", "missing-domain", "bad-ip", "wrong-domain",
            "dns-failure", "dns-mismatch", "mixed-dns", "config-failure", "systemd-failure"})
    void enableFailsClosedBeforeStartingAgainstInvalidConfigOrDns(String scenario) throws Exception {
        Path output = Files.createTempFile("caddy-enable-test-", ".log");
        try {
            var process = new ProcessBuilder(DeploymentScriptTest.bash(), "scripts/tests/caddy-enable-test.sh",
                    "scripts/tls/enable-caddy.sh", scenario).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            assertTrue(done);
            assertEquals(0, process.exitValue(), () -> { try { return Files.readString(output); } catch (Exception e) { return e.toString(); } });
        } finally { Files.deleteIfExists(output); }
    }
}
