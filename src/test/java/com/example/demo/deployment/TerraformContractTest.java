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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TerraformContractTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("terraform", name));
    }

    // Read a complete nested resource block rather than matching unrelated egress text.
    private static String block(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "Missing declaration: " + declaration);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("Unterminated block: " + declaration);
    }

    @Test
    void cliAndProviderVersionsAreExplicitAndCiUsesTheSameCli() throws Exception {
        String provider = source("provider.tf");
        assertTrue(provider.contains("required_version = \"= 1.15.6\""));
        assertTrue(provider.contains("version = \"~> 6.0\""));
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
        assertTrue(ci.contains("terraform_version: 1.15.6"));
        assertTrue(ci.contains("hashicorp/setup-terraform@v4.0.1"));
    }

    @Test
    void providerLockIsTrackedAndSecretsStateAndCachesRemainIgnored() throws Exception {
        String lock = source(".terraform.lock.hcl");
        assertTrue(lock.contains("provider \"registry.terraform.io/hashicorp/aws\""));
        assertTrue(lock.contains("version     = \"6.68.0\""));
        assertEquals(2, Pattern.compile("\"h1:[^\"]+\"").matcher(lock).results().count(),
                "Lock checksums cover both linux_amd64 and windows_amd64");
        for (String ignore : List.of(source(".gitignore"), Files.readString(Path.of(".gitignore")))) {
            assertFalse(ignore.contains(".terraform.lock.hcl"));
        }
        String ignore = source(".gitignore");
        for (String entry : List.of(".terraform/", "*.tfstate", "*.tfstate.*", "*.tfvars", "*.tfvars.json",
                "*.pem", "*.key", "id_ed25519*", "terraform_ed25519*")) assertTrue(ignore.contains(entry));
        var process = new ProcessBuilder("git", "ls-files", "--error-unmatch", "terraform/.terraform.lock.hcl")
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), "Provider lockfile must be committed with the module");
    }

    @Test
    void networkAndHostReferencesNeverRelyOnTheDefaultVpc() throws Exception {
        String main = source("main.tf");
        for (String type : List.of("aws_vpc", "aws_subnet", "aws_internet_gateway", "aws_route_table",
                "aws_route_table_association")) assertTrue(main.contains("resource \"" + type + "\""));
        assertFalse(main.contains("default_vpc"));
        assertFalse(main.contains("default = true"));
        assertTrue(main.contains("subnet_id                   = aws_subnet.public.id"));
        assertTrue(main.contains("associate_public_ip_address = false"));
        assertTrue(main.contains("depends_on = [aws_route_table_association.public]"));
        assertTrue(main.contains("sort(data.aws_availability_zones.available.names)[0]"));
    }

    @Test
    void ingressIsOnlyPublicHttpHttpsAndOperatorSshAndBootstrapEgressIsPreserved() throws Exception {
        String sg = block(source("main.tf"), "resource \"aws_security_group\" \"app_sg\"");
        String ingress = block(sg, "ingress");
        assertTrue(ingress.contains("from_port   = 22"));
        assertTrue(ingress.contains("to_port     = 22"));
        assertTrue(ingress.contains("cidr_blocks = [var.ssh_ingress_cidr]"));
        assertFalse(ingress.contains("0.0.0.0/0"));
        assertFalse(sg.contains("8080"));
        assertFalse(sg.contains("2019"));
        assertEquals(3, Pattern.compile("\\bingress\\s*\\{").matcher(sg).results().count());
        assertTrue(sg.contains("from_port   = 80"));
        assertTrue(sg.contains("from_port   = 443"));
        assertTrue(block(sg, "egress").contains("0.0.0.0/0"));
        assertTrue(sg.contains("vpc_id      = aws_vpc.app.id"));
        assertTrue(sg.contains("name_prefix"));
    }

    @Test
    void inputsRejectWorldOpenSshInvalidNetworksAndUnsupportedArchitecture() throws Exception {
        String variables = source("variables.tf");
        String ssh = block(variables, "variable \"ssh_ingress_cidr\"");
        assertFalse(Pattern.compile("\\bdefault\\s*=").matcher(ssh).find());
        assertTrue(ssh.contains("cidrnetmask(var.ssh_ingress_cidr)"));
        assertTrue(ssh.contains("tonumber(split(\"/\", var.ssh_ingress_cidr)[1]) > 0"));
        for (String variable : List.of("vpc_cidr", "public_subnet_cidr", "instance_type", "region")) {
            assertTrue(block(variables, "variable \"" + variable + "\"").contains("validation"));
        }
        String tests = source("tests/host.tftest.hcl");
        assertTrue(tests.contains("mock_provider \"aws\""));
        assertTrue(tests.contains("override_during = plan"));
        assertEquals(27, Pattern.compile("\\bcommand\\s*=\\s*plan\\b").matcher(tests).results().count());
        assertFalse(Pattern.compile("\\bcommand\\s*=\\s*apply\\b").matcher(tests).find());
    }

    @Test
    void imageIsResolvedFromAwsOfficialAl2023X86Parameter() throws Exception {
        String main = source("main.tf");
        assertTrue(main.contains("/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"));
        assertTrue(main.contains("ami                         = data.aws_ssm_parameter.al2023.value"));
        assertFalse(Pattern.compile("ami-[0-9a-f]{8,17}").matcher(main).find());
    }

    @Test
    void metadataAndRootVolumeAreHardenedWithoutAddingAnIamRole() throws Exception {
        String instance = block(source("main.tf"), "resource \"aws_instance\" \"app\"");
        assertTrue(instance.contains("http_tokens                 = \"required\""));
        assertTrue(instance.contains("http_put_response_hop_limit = 1"));
        String volume = block(instance, "root_block_device");
        for (String setting : List.of("encrypted             = true", "delete_on_termination = true",
                "volume_type           = \"gp3\"", "volume_size           = 30")) assertTrue(volume.contains(setting));
        assertFalse(instance.contains("iam_instance_profile"));
        assertFalse(source("main.tf").contains("resource \"aws_iam"));
    }

    @Test
    void tagsAndPublicKeyAreConsistentAndOutputsContainNoSecrets() throws Exception {
        assertTrue(source("provider.tf").contains("tags = local.common_tags"));
        String main = source("main.tf");
        for (String tag : List.of("Project", "ManagedBy", "Name")) assertTrue(main.contains(tag));
        assertTrue(main.contains("public_key = file(pathexpand(var.public_key_path))"));
        assertTrue(source("variables.tf").contains("readable OpenSSH public key file"));
        String outputs = source("outputs.tf");
        assertEquals(2, Pattern.compile("\\boutput\\s+\"").matcher(outputs).results().count());
        assertTrue(outputs.contains("output \"public_ip\""));
        assertTrue(outputs.contains("output \"instance_id\""));
    }

    @Test
    void bootstrapMatchesDeployHostToolsJavaHomeAndCheckoutOwnership() throws Exception {
        String script = source("userdata.sh");
        for (String tool : List.of("git", "java-17-amazon-corretto-devel", "docker", "curl-minimal", "util-linux",
                "flock", "sha256sum", "javac")) assertTrue(script.contains(tool));
        assertTrue(script.contains("export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto"));
        assertTrue(script.contains("systemctl enable --now docker"));
        assertTrue(script.contains("usermod -aG docker ec2-user"));
        assertTrue(script.contains("runuser -u ec2-user -- git clone --branch main --single-branch"));
        assertTrue(script.contains("/home/ec2-user/inventory-management-system"));
    }

    @Test
    void bootstrapContainsNoSecretsTracingApplicationStartupOrDbOperations() throws Exception {
        String script = source("userdata.sh");
        assertTrue(script.startsWith("#!/bin/bash\nset -euo pipefail\n"));
        assertFalse(script.contains("\r"));
        assertTrue(script.contains("umask 077"));
        for (String forbidden : List.of("set -x", "JWT_SECRET", "DB_PASSWORD", "DB_ROOT_PASSWORD", "BEGIN PRIVATE KEY",
                "GITHUB_TOKEN", "docker compose up", "inventory-db", "volume create", "volume rm", "restore.sh")) {
            assertFalse(script.contains(forbidden), "Forbidden bootstrap content: " + forbidden);
        }
    }

    @Test
    void composeDownloadIsPinnedAndVerifiedBeforeExecutableInstallation() throws Exception {
        String script = source("userdata.sh");
        assertTrue(script.contains("readonly compose_version=v2.40.3"));
        assertTrue(script.contains("readonly compose_sha256=dba9d98e1ba5bfe11d88c99b9bd32fc4a0624a30fafe68eea34d61a3e42fd372"));
        assertFalse(script.contains("latest/download"));
        assertTrue(script.contains("--fail --location"));
        assertTrue(script.contains("--proto '=https' --tlsv1.2"));
        assertTrue(script.indexOf("sha256sum --check --status") < script.indexOf("install -m 0755"));
        assertTrue(script.contains("docker compose version --short"));
    }

    @Test
    void ciValidatesReadOnlyWithoutAwsCredentialsPlanOrApply() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> ci = new Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml")));
        @SuppressWarnings("unchecked")
        var jobs = (Map<String, Map<String, Object>>) ci.get("jobs");
        var job = jobs.get("terraform");
        assertEquals(Map.of("contents", "read"), job.get("permissions"));
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) job.get("steps");
        for (String command : List.of("terraform fmt -check -recursive terraform",
                "terraform init -backend=false -input=false -lockfile=readonly", "terraform validate", "terraform test -no-color")) {
            var step = steps.stream().filter(s -> command.equals(s.get("run"))).findFirst().orElseThrow();
            assertFalse(step.containsKey("continue-on-error"));
            assertFalse(step.containsKey("if"));
        }
        String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));
        assertFalse(workflow.contains("terraform apply"));
        assertFalse(workflow.contains("terraform plan"));
        assertFalse(workflow.contains("AWS_ACCESS_KEY"));
        assertFalse(job.containsKey("continue-on-error"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"success", "wrong-arch", "package-failure", "wrong-java", "download-failure",
            "checksum-mismatch", "wrong-compose", "service-failure", "clone-failure",
            "caddy-download-failure", "caddy-checksum-mismatch", "wrong-caddy", "caddy-config-failure",
            "caddy-systemd-failure"})
    void bootstrapFailsClosedUsingOnlyMockedHostCommands(String scenario) throws Exception {
        Path output = Files.createTempFile("terraform-userdata-test-", ".log");
        try {
            var process = new ProcessBuilder(DeploymentScriptTest.bash(), "terraform/tests/userdata-test.sh",
                    "terraform/userdata.sh", scenario).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "Bootstrap mock test timed out: " + scenario);
            assertEquals(0, process.exitValue(), "Bootstrap safety regression failed: " + scenario);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
