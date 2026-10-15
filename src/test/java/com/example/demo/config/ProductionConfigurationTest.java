package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ProductionConfigurationTest {
    private static final String PASSWORD = "private-db-password-for-tests";
    private static final String SECRET = "private-jwt-secret-for-tests-0123456789";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ProductionConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod");

    private Map<String, String> valid() {
        var values = new LinkedHashMap<String, String>();
        values.put("spring.datasource.url", "jdbc:mariadb://db:3306/asset_management");
        values.put("spring.datasource.username", "inventory_app");
        values.put("spring.datasource.password", PASSWORD);
        values.put("JWT_SECRET", SECRET);
        values.put("app.login.rate-limit.trusted-proxies", "127.0.0.1");
        return values;
    }

    static Stream<Arguments> missingSettings() {
        return Stream.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "JWT_SECRET")
                .flatMap(property -> Stream.of("", " ").map(value -> Arguments.of(property, value)));
    }

    @ParameterizedTest
    @MethodSource("missingSettings")
    void missingOrBlankRequiredConfigFailsBeforeDatabaseInitialization(String property, String value, CapturedOutput output) {
        var values = valid();
        values.put(property, value);
        fails(values, "is required");
        assertNoSecrets(output);
    }

    @Test
    void entirelyUnsetProductionSettingsFailFast(CapturedOutput output) {
        runner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("SPRING_DATASOURCE_URL is required"));
        });
        assertNoSecrets(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "ROOT", " root "})
    void rootDatasourceIsRejectedWithoutLoggingValues(String username, CapturedOutput output) {
        var values = valid();
        values.put("spring.datasource.username", username);
        fails(values, "must not be root");
        assertNoSecrets(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mariadb://db/test?password=private-url-password",
            "jdbc:mariadb://user:private-url-password@db/test",
            "jdbc:mariadb://db/test?%70assword=private-url-password"})
    void urlCredentialsAreRejectedWithoutLeakingThem(String url, CapturedOutput output) {
        var values = valid();
        values.put("spring.datasource.url", url);
        fails(values, "must not contain credentials");
        assertNoSecrets(output);
        assertFalse(output.getAll().contains("private-url-password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "0123456789012345678901234567890", "ああああああああああ"})
    void weakSecretIsRejectedBeforeDatabaseInitialization(String secret, CapturedOutput output) {
        var values = valid();
        values.put("JWT_SECRET", secret);
        fails(values, "at least 32 bytes");
        assertNoSecrets(output);
    }

    @Test
    void dedicatedDatasourceAndSufficientUtf8SecretPassValidation() {
        var values = valid();
        values.put("JWT_SECRET", "あ".repeat(11));
        runner.withPropertyValues(properties(values)).run(context -> assertNull(context.getStartupFailure()));
    }

    private void fails(Map<String, String> values, String message) {
        runner.withPropertyValues(properties(values)).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains(message));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "localhost", "172.18.0.0/16", "*", "127.0.0.1,invalid-peer"})
    void absentOrInvalidProxyTrustFailsBeforeDatabaseInitialization(String peers, CapturedOutput output) {
        var values = valid();
        values.put("app.login.rate-limit.trusted-proxies", peers);
        runner.withPropertyValues(properties(values)).run(context -> assertNotNull(context.getStartupFailure()));
        assertNoSecrets(output);
    }

    @Test
    void productionCannotDisableVerifiedProxyRequirement(CapturedOutput output) {
        var values = valid();
        values.put("app.login.rate-limit.require-trusted-proxy", "false");
        fails(values, "Production login requires a trusted proxy");
        assertNoSecrets(output);
    }

    @Test
    void nativeForwardingCannotRewriteTransportBeforeCapture(CapturedOutput output) {
        var values = valid();
        values.put("server.forward-headers-strategy", "native");
        fails(values, "Production forwarding must use framework");
        assertNoSecrets(output);
    }

    private String[] properties(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private void assertNoSecrets(CapturedOutput output) {
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains(SECRET));
    }
}
