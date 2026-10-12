package com.example.demo.provisioning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/** Explicit offline entry point. Does not start Spring, HTTP, Flyway, JWT or the application. */
public final class InitialAdminCommand {
    private InitialAdminCommand() { }

    public static void main(String[] args) {
        // JDBC exceptions may contain credentials/parameters. This isolated process emits only status codes.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(Level.OFF);
        Console console = System.console();
        System.exit(execute(args, System.getenv(), System.in,
                console == null ? null : console::readPassword, System.out, DriverManager::getConnection));
    }

    @FunctionalInterface
    interface PasswordConsole { char[] read(String prompt); }

    @FunctionalInterface
    interface Connector { java.sql.Connection open(String url, Properties properties) throws SQLException; }

    static int execute(String[] args, Map<String, String> environment, InputStream stdin,
                       PasswordConsole console, PrintStream output, Connector connector) {
        char[] databasePassword = null;
        try {
            boolean pipe = args.length == 1 && "--passwords-stdin".equals(args[0]);
            if ((!pipe && args.length != 0) || (pipe && console != null)) {
                return status(output, "INVALID_INVOCATION", 64);
            }
            if (!pipe && console == null) return status(output, "CONSOLE_REQUIRED", 64);
            String url = environment.get("PROVISION_JDBC_URL");
            String user = environment.get("PROVISION_DB_USER");
            String administrator = environment.get("PROVISION_ADMIN_USERNAME");
            validateConnectionSettings(url, user);
            InitialAdminProvisioner.validateUsername(administrator);
            databasePassword = pipe ? readPipeSecret(stdin) : console.read("Database password: ");
            if (databasePassword == null || databasePassword.length == 0 || databasePassword.length > 1024) {
                return status(output, "INVALID_INPUT", 64);
            }
            char[] credential = databasePassword;
            var result = new InitialAdminProvisioner().provision(() -> {
                Properties properties = new Properties();
                properties.setProperty("user", user);
                properties.setProperty("password", new String(credential));
                properties.setProperty("connectTimeout", "5000");
                properties.setProperty("socketTimeout", "15000");
                try { return connector.open(url, properties); }
                finally { properties.clear(); Arrays.fill(credential, '\0'); }
            }, administrator, () -> readNewPassword(pipe, stdin, console));
            return status(output, result.name(), switch (result) {
                case CREATED, ADMIN_PRESENT -> 0;
                case USERNAME_TAKEN -> 65;
                case LOCK_BUSY -> 75;
                case SCHEMA_NOT_READY -> 78;
            });
        } catch (IllegalArgumentException | IOException invalid) {
            return status(output, "INVALID_INPUT", 64);
        } catch (SQLException | RuntimeException failure) {
            // Never print an exception, cause, URL, username, hash, password or token.
            return status(output, "DATABASE_FAILED", 74);
        } finally {
            if (databasePassword != null) Arrays.fill(databasePassword, '\0');
        }
    }

    static void validateConnectionSettings(String url, String user) {
        if (url == null || !url.matches("jdbc:mariadb://(?:[A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+])"
                + "(?::[0-9]{1,5})?/[A-Za-z][A-Za-z0-9_]*(?:\\?sslMode=verify-full)?")
                || user == null || !user.matches("[A-Za-z][A-Za-z0-9_]{0,79}") || "root".equalsIgnoreCase(user)) {
            throw new IllegalArgumentException("Invalid connection settings");
        }
    }

    private static char[] readNewPassword(boolean pipe, InputStream stdin, PasswordConsole console) throws IOException {
        char[] first = null;
        char[] confirmation = null;
        boolean transferred = false;
        try {
            first = pipe ? readPipeSecret(stdin) : console.read("New initial ADMIN password: ");
            confirmation = pipe ? readPipeSecret(stdin) : console.read("Confirm initial ADMIN password: ");
            if (first == null || confirmation == null || !Arrays.equals(first, confirmation)) {
                throw new IllegalArgumentException("Password confirmation failed");
            }
            InitialAdminProvisioner.validatePassword(first);
            transferred = true;
            return first;
        } finally {
            if (confirmation != null) Arrays.fill(confirmation, '\0');
            if (!transferred && first != null) Arrays.fill(first, '\0');
        }
    }

    static char[] readPipeSecret(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        CharBuffer decoded = null;
        try {
            int length = 0;
            int value;
            while ((value = input.read()) != '\n') {
                if (value == -1 || length == buffer.length) throw new IOException("Incomplete password record");
                buffer[length++] = (byte) value;
            }
            if (length > 0 && buffer[length - 1] == '\r') length--;
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer, 0, length));
            char[] secret = new char[decoded.remaining()];
            decoded.get(secret);
            return secret;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            if (decoded != null && decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        }
    }

    private static int status(PrintStream output, String status, int exit) {
        output.println("initial-admin: " + status);
        return exit;
    }
}
