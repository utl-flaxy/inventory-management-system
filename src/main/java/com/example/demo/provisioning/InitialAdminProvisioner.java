package com.example.demo.provisioning;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Plain JDBC operation, deliberately neither a Spring bean nor an application runner. */
public final class InitialAdminProvisioner {
    public enum Result {
        CREATED, ADMIN_PRESENT, USERNAME_TAKEN, LOCK_BUSY, SCHEMA_NOT_READY
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        /** Must return a newly opened, unpooled connection owned by this operation. */
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface PasswordSource {
        /** Ownership of the returned array passes to the provisioner, which clears it. */
        char[] read() throws IOException;
    }

    public Result provision(ConnectionFactory connections, String username, PasswordSource passwords)
            throws SQLException, IOException {
        validateUsername(username);
        try (Connection connection = connections.open()) {
            if (!connection.getAutoCommit()) throw new SQLException("A fresh connection is required");
            if (!schemaReady(connection)) return Result.SCHEMA_NOT_READY;
            String lock = lockName(connection.getCatalog());
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                statement.setString(1, lock);
                statement.setQueryTimeout(15);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getObject(1) == null) throw new SQLException("Lock acquisition failed");
                    if (rows.getInt(1) != 1) return Result.LOCK_BUSY;
                }
            }
            // The first users read occurs only after the preceding owner's commit and connection close.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                if (adminExists(connection)) {
                    return Result.ADMIN_PRESENT;
                }
                if (usernameExists(connection, username)) {
                    return Result.USERNAME_TAKEN;
                }
                char[] password = passwords.read();
                try {
                    validatePassword(password);
                    String hash = new BCryptPasswordEncoder().encode(CharBuffer.wrap(password));
                    try (var insert = connection.prepareStatement(
                            "INSERT INTO users (username, password, role, auth_uid, token_version) VALUES (?, ?, 'ROLE_ADMIN', ?, 0)")) {
                        insert.setString(1, username);
                        insert.setString(2, hash);
                        insert.setString(3, java.util.UUID.randomUUID().toString());
                        if (insert.executeUpdate() != 1) throw new SQLException("Insert did not complete");
                    }
                } finally {
                    if (password != null) Arrays.fill(password, '\0');
                }
                connection.commit();
                return Result.CREATED;
            } finally {
                // Also covers validation/input/unique-constraint failures. No UPDATE, DELETE or DDL.
                connection.rollback();
            }
            // GET_LOCK is connection-scoped, not transaction-scoped. Owned connection close releases it,
            // including failure paths; the CLI never returns this connection to a pool.
        }
    }

    private boolean adminExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM users WHERE role = 'ROLE_ADMIN' LIMIT 1");
             var rows = statement.executeQuery()) {
            return rows.next();
        }
    }

    private boolean usernameExists(Connection connection, String username) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM users WHERE username = ? LIMIT 1")) {
            statement.setString(1, username);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean schemaReady(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1))) return false;
        }
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'users' AND ((CONSTRAINT_NAME = 'uk_users_username' AND CONSTRAINT_TYPE = 'UNIQUE') "
                        + "OR (CONSTRAINT_NAME = 'ck_users_role_allowed' AND CONSTRAINT_TYPE = 'CHECK') "
                        + "OR (CONSTRAINT_NAME = 'uk_users_auth_uid' AND CONSTRAINT_TYPE = 'UNIQUE') "
                        + "OR (CONSTRAINT_NAME IN ('ck_users_auth_uid','ck_users_token_version') AND CONSTRAINT_TYPE = 'CHECK'))");
             var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 5) return false;
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'");
             var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 1) return false;
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1 AND checksum=1360701");
             var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 1) return false;
        }
        // Server-local advisory locks cannot serialize writers routed to different Galera nodes.
        try (var statement = connection.prepareStatement("SELECT @@wsrep_on");
             var rows = statement.executeQuery()) {
            return rows.next() && rows.getInt(1) == 0;
        }
    }

    public static String lockName(String catalog) {
        if (catalog == null || catalog.isBlank()) throw new IllegalArgumentException("Database is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(catalog.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "inventory.initial-admin." + HexFormat.of().formatHex(digest, 0, 20);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("Invalid administrator username");
        }
    }

    public static void validatePassword(char[] password) {
        if (password == null || password.length < 16 || password.length > 72) {
            throw new IllegalArgumentException("Password must contain 16 to 72 characters");
        }
        java.nio.ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password));
            if (encoded.remaining() > 72 || CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)
                    || CharBuffer.wrap(password).chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid administrator password");
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid password encoding");
        } finally {
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
