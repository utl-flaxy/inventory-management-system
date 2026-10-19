package com.example.demo.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AdminAuditMigrationIT {
    @Test void unexpectedMissingConstraintStopsV9WithoutChangingExistingUsersOrAudits() {
        try (var isolated = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("auth_v9_interrupted")) {
            isolated.start();
            var source = new DriverManagerDataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword());
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("8").load().migrate();
            jdbc.update("INSERT INTO users(username,password,role,auth_uid,token_version) VALUES('preserved','private-fixture','ROLE_ADMIN',?,7)", UUID.randomUUID().toString());
            jdbc.update("INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES(1,1,'PASSWORD_CHANGE','SUCCEEDED',CURRENT_TIMESTAMP(6),?)", UUID.randomUUID().toString());
            var rows = jdbc.queryForList("SELECT * FROM users");var audits = jdbc.queryForList("SELECT * FROM auth_revocation_audit");
            var history = jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank");
            // Isolated fault injection models unexpected/partially changed DDL. No automatic repair/clean.
            jdbc.execute("ALTER TABLE auth_revocation_audit DROP CONSTRAINT ck_auth_revocation_action");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> Flyway.configure().dataSource(source).load().migrate());
            assertTrue(rows.equals(jdbc.queryForList("SELECT * FROM users")), "Failed DDL changed users");
            assertTrue(audits.equals(jdbc.queryForList("SELECT * FROM auth_revocation_audit")), "Failed DDL changed audits");
            assertTrue(history.equals(jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history WHERE version<>'9' ORDER BY installed_rank")));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=1", Integer.class));
        }
    }
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("auth_v9_migration");

    @Test void v9PreservesEveryExistingRowAndV1ThroughV8HistoryAndAllowsOnlyReviewedActions() {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("8").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password,role,auth_uid,token_version) VALUES(1,'existing','preserved-private-hash','ROLE_USER',?,3)", UUID.randomUUID().toString());
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'preserved-product',100,8,2)");
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at) VALUES(1,1,200,'PENDING','2026-01-01','2026-01-01')");
        jdbc.update("INSERT INTO order_items(id,quantity,price,product_name_snapshot,order_id,product_id) VALUES(1,2,100,'snapshot',1,1)");
        String insert = "INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES(1,1,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),?)";
        jdbc.update(insert, "LOGOUT_ALL", UUID.randomUUID().toString());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, "FORCE_REVOKE", UUID.randomUUID().toString()));
        jdbc.update(insert, "PASSWORD_CHANGE", UUID.randomUUID().toString());
        var tables = List.of("users", "products", "orders", "order_items", "auth_revocation_audit");
        var rows = tables.stream().map(t -> jdbc.queryForList("SELECT * FROM " + t + " ORDER BY id")).toList();
        var history = jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank");
        var upgraded = Flyway.configure().dataSource(source).load();assertEquals(1, upgraded.migrate().migrationsExecuted);
        for (int i = 0; i < tables.size(); i++) assertTrue(rows.get(i).equals(jdbc.queryForList("SELECT * FROM " + tables.get(i) + " ORDER BY id")), "Migration changed existing rows");
        assertTrue(history.equals(jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history WHERE version<>'9' ORDER BY installed_rank")), "Historical migration metadata changed");
        assertEquals("9", upgraded.info().current().getVersion().getVersion());
        jdbc.update(insert, "PASSWORD_CHANGE", UUID.randomUUID().toString());jdbc.update(insert, "LOGOUT_ALL", UUID.randomUUID().toString());
        for (String action : List.of("force_revoke", "password_change", "UNKNOWN"))
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, action, UUID.randomUUID().toString()));
        jdbc.update(insert, "FORCE_REVOKE", UUID.randomUUID().toString());
        var audits = jdbc.queryForList("SELECT * FROM auth_revocation_audit ORDER BY id");
        upgraded.validate();assertEquals(0, upgraded.migrate().migrationsExecuted);
        assertTrue(audits.equals(jdbc.queryForList("SELECT * FROM auth_revocation_audit ORDER BY id")), "Repeat migration changed audit history");
    }
}
