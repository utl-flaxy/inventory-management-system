package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;

/** Additive, restartable MariaDB DDL. Never rewrite this version after release. */
public class V7__add_auth_identity_and_revocations extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }
    @Override public Integer getChecksum() { return 1360701; }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        requireInnoDb(connection, "users");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_uid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
            statement.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0");
        }
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='users' AND ((COLUMN_NAME='auth_uid' AND DATA_TYPE='char' AND CHARACTER_MAXIMUM_LENGTH=36 "
                + "AND CHARACTER_SET_NAME='ascii' AND COLLATION_NAME='ascii_bin') OR (COLUMN_NAME='token_version' "
                    + "AND DATA_TYPE='bigint' AND COLUMN_TYPE NOT LIKE '%unsigned%' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0'))");
             var rows = query.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 2) throw new SQLException("Unexpected partial authentication schema; operator review required");
        }
        // Batches are bounded; already allocated identities survive a partially completed migration.
        while (true) {
            var ids = new ArrayList<Long>();
            try (var query = connection.prepareStatement("SELECT id FROM users WHERE auth_uid IS NULL ORDER BY id LIMIT 200");
                 var rows = query.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
            if (ids.isEmpty()) break;
            try (var update = connection.prepareStatement("UPDATE users SET auth_uid = ? WHERE id = ? AND auth_uid IS NULL")) {
                for (Long id : ids) {
                    update.setString(1, UUID.randomUUID().toString());
                    update.setLong(2, id);
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE auth_uid IS NULL OR "
                + "NOT (BINARY auth_uid REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') "
                + "OR token_version IS NULL OR token_version < 0"); var rows = query.executeQuery()) {
            if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("Invalid authentication identity state; operator review required");
        }
        try (var query = connection.prepareStatement("SELECT auth_uid FROM users GROUP BY auth_uid HAVING COUNT(*) > 1 LIMIT 1");
             var rows = query.executeQuery()) {
            if (rows.next()) throw new SQLException("Duplicate authentication identity; operator review required");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users MODIFY COLUMN auth_uid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        }
        if (!constraintExists(connection, "uk_users_auth_uid")) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD CONSTRAINT uk_users_auth_uid UNIQUE (auth_uid)");
            }
        }
        if (!constraintExists(connection, "ck_users_auth_uid")) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD CONSTRAINT ck_users_auth_uid CHECK "
                        + "(BINARY auth_uid REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')");
            }
        }
        if (!constraintExists(connection, "ck_users_token_version")) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD CONSTRAINT ck_users_token_version CHECK (token_version >= 0)");
            }
        }
        requireCheck(connection, "users", "ck_users_auth_uid", new String[]{
                "BINARYauth_uidREGEXP'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'",
                "castauth_uidascharcharsetbinaryregexp'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'"});
        requireCheck(connection, "users", "ck_users_token_version", new String[]{"token_version>=0"});
        try (var query = connection.prepareStatement("SELECT k.COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE k "
                + "JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA "
                + "AND t.TABLE_NAME=k.TABLE_NAME AND t.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='users' AND k.CONSTRAINT_NAME='uk_users_auth_uid' "
                + "AND t.CONSTRAINT_TYPE='UNIQUE' ORDER BY k.ORDINAL_POSITION"); var rows = query.executeQuery()) {
            if (!rows.next() || !"auth_uid".equals(rows.getString(1)) || rows.next())
                throw new SQLException("Unexpected authentication unique constraint; operator review required");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auth_revocation_audit ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, actor_user_id BIGINT NOT NULL, target_user_id BIGINT NOT NULL, "
                    + "action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                    + "result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                    + "created_at DATETIME(6) NOT NULL, request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                    + "CONSTRAINT ck_auth_revocation_action CHECK (action = 'LOGOUT_ALL'), "
                    + "CONSTRAINT ck_auth_revocation_result CHECK (result = 'SUCCEEDED'), "
                    + "INDEX idx_auth_revocation_target_created (target_user_id,created_at)) ENGINE=InnoDB");
        }
        requireInnoDb(connection, "auth_revocation_audit");
        requireCheck(connection, "auth_revocation_audit", "ck_auth_revocation_action", new String[]{"action='LOGOUT_ALL'"});
        requireCheck(connection, "auth_revocation_audit", "ck_auth_revocation_result", new String[]{"result='SUCCEEDED'"});
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='auth_revocation_audit' AND IS_NULLABLE='NO' AND ((COLUMN_NAME IN ('id','actor_user_id','target_user_id') "
                + "AND DATA_TYPE='bigint') OR (COLUMN_NAME='action' AND DATA_TYPE='varchar' AND CHARACTER_MAXIMUM_LENGTH=24) "
                + "OR (COLUMN_NAME='result' AND DATA_TYPE='varchar' AND CHARACTER_MAXIMUM_LENGTH=16) "
                + "OR (COLUMN_NAME='created_at' AND DATA_TYPE='datetime' AND DATETIME_PRECISION=6) "
                + "OR (COLUMN_NAME='request_id' AND DATA_TYPE='char' AND CHARACTER_MAXIMUM_LENGTH=36))"); var rows = query.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 7) throw new SQLException("Unexpected authentication audit schema; operator review required");
        }
    }

    private void requireInnoDb(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1)))
                    throw new SQLException("Authentication persistence requires InnoDB");
            }
        }
    }

    private boolean constraintExists(Connection connection, String name) throws SQLException {
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND CONSTRAINT_NAME = ?")) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) { return rows.next() && rows.getInt(1) == 1; }
        }
    }

    private void requireCheck(Connection connection, String table, String name, String[] expected) throws SQLException {
        try (var query = connection.prepareStatement("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?")) {
            query.setString(1, table); query.setString(2, name);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing authentication check; operator review required");
                // MariaDB canonicalizes BINARY to CAST; do not case-fold quoted regex/alphabet values.
                String clause = rows.getString(1).replace("`", "").replace("(", "").replace(")", "").replaceAll("\\s+", "");
                if (!java.util.Arrays.asList(expected).contains(clause) || rows.next())
                    throw new SQLException("Unexpected authentication check; operator review required");
            }
        }
    }
}
