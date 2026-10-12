package com.example.demo.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InitialAdminCommandTest {
    private static final Map<String, String> SETTINGS = Map.of(
            "PROVISION_JDBC_URL", "jdbc:mariadb://db:3306/inventory",
            "PROVISION_DB_USER", "bootstrap_operator", "PROVISION_ADMIN_USERNAME", "initial_operator");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "has space", "role';--", "管理者", "a\nname"})
    void invalidUsernamesCannotReachDatabase(String username) {
        assertThrows(IllegalArgumentException.class, () -> InitialAdminProvisioner.validateUsername(username));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "                ", "0123456789abcde\n", "0123456789abcde\uD800"})
    void unsafePasswordsAreRejected(String password) {
        assertThrows(IllegalArgumentException.class, () -> InitialAdminProvisioner.validatePassword(password.toCharArray()));
    }

    @Test
    void bcryptByteBoundaryIsEnforcedWithoutTrimming() {
        assertDoesNotThrow(() -> InitialAdminProvisioner.validatePassword("a".repeat(72).toCharArray()));
        assertDoesNotThrow(() -> InitialAdminProvisioner.validatePassword("あ".repeat(24).toCharArray()));
        assertDoesNotThrow(() -> InitialAdminProvisioner.validatePassword((" " + "a".repeat(15)).toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> InitialAdminProvisioner.validatePassword("あ".repeat(25).toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> InitialAdminProvisioner.validatePassword("a".repeat(73).toCharArray()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://db/inventory", "jdbc:mariadb://user:secret@db/inventory",
            "jdbc:mariadb://db/inventory?password=private", "jdbc:mariadb://db/inventory?allowMultiQueries=true",
            "jdbc:mariadb://db/inventory?sslMode=disable", "jdbc:mariadb://db/inventory;secret"})
    void credentialBearingOrUnsafeJdbcSettingsAreRejected(String url) {
        assertThrows(IllegalArgumentException.class, () -> InitialAdminCommand.validateConnectionSettings(url, "operator"));
    }

    @Test
    void remoteTlsAndIpv6SettingsAreAcceptedButRootIsRejected() {
        assertDoesNotThrow(() -> InitialAdminCommand.validateConnectionSettings(
                "jdbc:mariadb://db.example:3306/inventory?sslMode=verify-full", "operator"));
        assertDoesNotThrow(() -> InitialAdminCommand.validateConnectionSettings("jdbc:mariadb://[::1]/inventory", "operator"));
        assertThrows(IllegalArgumentException.class, () -> InitialAdminCommand.validateConnectionSettings(SETTINGS.get("PROVISION_JDBC_URL"), "root"));
    }

    @Test
    void defaultInvocationRequiresRealConsoleAndNeverFallsBackToStdin() {
        var output = new ByteArrayOutputStream();
        int status = InitialAdminCommand.execute(new String[0], SETTINGS,
                new ByteArrayInputStream("not-a-console-secret\n".getBytes(StandardCharsets.UTF_8)), null,
                new PrintStream(output), (url, properties) -> { fail("No connection without console"); return null; });
        assertEquals(64, status);
        assertEquals("initial-admin: CONSOLE_REQUIRED", output.toString(StandardCharsets.UTF_8).strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--password=do-not-print-me", "do-not-print-me", "--help", "--promote-user"})
    void unrecognizedArgumentsAreNotEchoedOrUsed(String argument) {
        var output = new ByteArrayOutputStream();
        int status = InitialAdminCommand.execute(new String[]{argument}, SETTINGS, Input.empty(), prompt -> {
            fail("Arguments must be rejected before password input"); return null;
        }, new PrintStream(output), (url, properties) -> { fail("No database access"); return null; });
        assertEquals(64, status);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(argument));
    }

    @Test
    void pipeModeMustBeExplicitAndCannotEchoInteractiveInput() {
        var output = new ByteArrayOutputStream();
        int status = InitialAdminCommand.execute(new String[]{"--passwords-stdin"}, SETTINGS, Input.empty(),
                prompt -> { fail("Interactive pipe mode must be refused"); return null; }, new PrintStream(output),
                (url, properties) -> { fail("No connection"); return null; });
        assertEquals(64, status);
    }

    @Test
    void connectionFailureDoesNotExposeCauseCredentialsOrJdbcSettingsAndClearsConsoleArray() {
        char[] secret = "synthetic-database-credential".toCharArray();
        var output = new ByteArrayOutputStream();
        AtomicBoolean called = new AtomicBoolean();
        int status = InitialAdminCommand.execute(new String[0], SETTINGS, Input.empty(), prompt -> secret,
                new PrintStream(output), (url, properties) -> {
                    called.set(true);
                    assertEquals("synthetic-database-credential", properties.getProperty("password"));
                    throw new SQLException("synthetic-database-credential / hash / JWT / " + url);
                });
        assertTrue(called.get());
        assertEquals(74, status);
        assertEquals("initial-admin: DATABASE_FAILED", output.toString(StandardCharsets.UTF_8).strip());
        assertTrue(Arrays.equals(new char[secret.length], secret));
    }

    @Test
    void stdinProtocolHandlesUtf8CrLfAndRefusesUnterminatedOversizedOrMalformedRecords() throws Exception {
        assertArrayEquals("日本語".toCharArray(), InitialAdminCommand.readPipeSecret(
                new ByteArrayInputStream("日本語\r\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> InitialAdminCommand.readPipeSecret(
                new ByteArrayInputStream("no-newline".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> InitialAdminCommand.readPipeSecret(
                new ByteArrayInputStream(("a".repeat(4097) + "\n").getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> InitialAdminCommand.readPipeSecret(new ByteArrayInputStream(new byte[]{(byte) 0xff, 10})));
    }

    @Test
    void databaseSpecificMutexNameIsBoundedAndCanonical() {
        assertEquals(InitialAdminProvisioner.lockName("Inventory"), InitialAdminProvisioner.lockName("inventory"));
        assertNotEquals(InitialAdminProvisioner.lockName("first"), InitialAdminProvisioner.lockName("second"));
        assertTrue(InitialAdminProvisioner.lockName("inventory").length() <= 64);
    }

    private static class Input {
        static ByteArrayInputStream empty() { return new ByteArrayInputStream(new byte[0]); }
    }
}
