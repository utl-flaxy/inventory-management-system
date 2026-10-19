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
class PasswordAuditMigrationIT {
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("auth_v8_migration");

    @Test void v8PreservesEveryExistingRowAndV1ThroughV7HistoryAndAllowsOnlyReviewedActions() {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("7").load().migrate();
        jdbc.update("INSERT INTO users(id,username,password,role,auth_uid,token_version) VALUES(1,'existing','preserved-private-hash','ROLE_USER',?,3)", UUID.randomUUID().toString());
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'preserved-product',100,8,2)");
        jdbc.update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at) VALUES(1,1,200,'PENDING','2026-01-01','2026-01-01')");
        jdbc.update("INSERT INTO order_items(id,quantity,price,product_name_snapshot,order_id,product_id) VALUES(1,2,100,'snapshot',1,1)");
        String insert = "INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES(1,1,?,'SUCCEEDED',CURRENT_TIMESTAMP(6),?)";
        jdbc.update(insert, "LOGOUT_ALL", UUID.randomUUID().toString());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, "PASSWORD_CHANGE", UUID.randomUUID().toString()));
        var tables = List.of("users", "products", "orders", "order_items", "auth_revocation_audit");
        var rows = tables.stream().map(t -> jdbc.queryForList("SELECT * FROM " + t + " ORDER BY id")).toList();
        var history = jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history ORDER BY installed_rank");
        var upgraded = Flyway.configure().dataSource(source).target("8").load();assertEquals(1, upgraded.migrate().migrationsExecuted);
        for (int i = 0; i < tables.size(); i++) assertTrue(rows.get(i).equals(jdbc.queryForList("SELECT * FROM " + tables.get(i) + " ORDER BY id")), "Migration changed existing rows");
        assertTrue(history.equals(jdbc.queryForList("SELECT version,checksum,success,installed_on FROM flyway_schema_history WHERE version<>'8' ORDER BY installed_rank")), "Historical migration metadata changed");
        assertEquals("8", upgraded.info().current().getVersion().getVersion());
        jdbc.update(insert, "PASSWORD_CHANGE", UUID.randomUUID().toString());jdbc.update(insert, "LOGOUT_ALL", UUID.randomUUID().toString());
        for (String action : List.of("FORCE_REVOKE", "password_change", "UNKNOWN"))
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, action, UUID.randomUUID().toString()));
        var audits = jdbc.queryForList("SELECT * FROM auth_revocation_audit ORDER BY id");
        upgraded.validate();assertEquals(0, upgraded.migrate().migrationsExecuted);
        assertTrue(audits.equals(jdbc.queryForList("SELECT * FROM auth_revocation_audit ORDER BY id")), "Repeat migration changed audit history");
    }
}
