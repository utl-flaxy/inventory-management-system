package com.example.demo.repository;

import db.migration.V7__add_auth_identity_and_revocations;
import com.example.demo.security.AuthIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class AuthIdentityMigrationIT {
    @Container final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2").withDatabaseName("auth_v7_migration");

    private DriverManagerDataSource source() {
        return new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(source()); }
    private Flyway flyway(String target) { return Flyway.configure().dataSource(source()).target(target).load(); }
    private void legacy() {
        flyway("6").migrate();
        jdbc().update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','private-legacy-hash','ROLE_USER'),(2,'operator','private-admin-hash','ROLE_ADMIN')");
        jdbc().update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'existing-product',100,8,2)");
        jdbc().update("INSERT INTO orders(id,customer_id,total_price,status,ordered_at,updated_at) VALUES(1,1,200,'PENDING','2026-01-01','2026-01-01')");
        jdbc().update("INSERT INTO order_items(id,quantity,price,product_name_snapshot,order_id,product_id) VALUES(1,2,100,'existing-product',1,1)");
    }

    @Test void existingUsersCredentialsRolesBusinessRowsAndMigrationChecksumsSurvive() {
        legacy();
        var beforeUsers = jdbc().queryForList("SELECT id,username,password,role FROM users ORDER BY id");
        var beforeOrders = jdbc().queryForList("SELECT * FROM orders ORDER BY id");
        var beforeItems = jdbc().queryForList("SELECT * FROM order_items ORDER BY id");
        var beforeProducts = jdbc().queryForList("SELECT * FROM products ORDER BY id");
        var oldHistory = jdbc().queryForList("SELECT version,checksum,installed_on FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank");
        assertEquals(1,flyway("7").migrate().migrationsExecuted);
        assertTrue(beforeUsers.equals(jdbc().queryForList("SELECT id,username,password,role FROM users ORDER BY id")),"Existing user state changed");
        assertTrue(beforeOrders.equals(jdbc().queryForList("SELECT * FROM orders ORDER BY id")),"Order state changed");
        assertTrue(beforeItems.equals(jdbc().queryForList("SELECT * FROM order_items ORDER BY id")),"Item state changed");
        assertTrue(beforeProducts.equals(jdbc().queryForList("SELECT * FROM products ORDER BY id")),"Stock state changed");
        assertTrue(oldHistory.equals(jdbc().queryForList("SELECT version,checksum,installed_on FROM flyway_schema_history WHERE success=1 AND version<>'7' ORDER BY installed_rank")),"V1-V6 history changed");
        var identities=jdbc().queryForList("SELECT id,auth_uid,token_version FROM users ORDER BY id");
        for(var row:identities) { AuthIdentity.requireUid((String)row.get("auth_uid"));assertEquals(0L,((Number)row.get("token_version")).longValue()); }
        assertEquals(2,jdbc().queryForObject("SELECT COUNT(DISTINCT auth_uid) FROM users",Integer.class));
        assertEquals(0,flyway("7").migrate().migrationsExecuted);
        assertTrue(identities.equals(jdbc().queryForList("SELECT id,auth_uid,token_version FROM users ORDER BY id")),"Identity changed on restart");
        assertEquals(1360701,jdbc().queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='7' AND success=1",Integer.class));
        assertEquals(0,jdbc().queryForObject("SELECT COUNT(*) FROM auth_revocation_audit",Integer.class));
        System.out.println("ISSUE-136 migration: V6 users/hash/role/FK/business preserved; UUIDv4 unique; repeat unchanged");
    }

    @Test void interruptedDdlCanResumeWithoutReplacingAlreadyAllocatedIdentities() throws Exception {
        legacy();
        var once=new AtomicBoolean();
        try(Connection real=source().getConnection()) {
            Connection fault=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
                try {
                    Object result=method.invoke(real,args);
                    if(!method.getName().equals("createStatement"))return result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)-> {
                        if(m.getName().equals("execute") && ((String)a[0]).contains("ck_users_token_version") && once.compareAndSet(false,true))
                            throw new SQLException("Injected additive DDL interruption");
                        try {return m.invoke(result,a);}catch(InvocationTargetException ex){throw ex.getCause();}
                    });
                }catch(InvocationTargetException ex){throw ex.getCause();}
            });
            var interrupted=mock(Context.class);when(interrupted.getConnection()).thenReturn(fault);
            assertThrows(SQLException.class,()->new V7__add_auth_identity_and_revocations().migrate(interrupted));
            assertTrue(once.get());
            var allocated=jdbc().queryForList("SELECT id,auth_uid,token_version FROM users ORDER BY id");
            assertEquals(2,allocated.size());
            var resumed=mock(Context.class);when(resumed.getConnection()).thenReturn(real);
            new V7__add_auth_identity_and_revocations().migrate(resumed);
            assertTrue(allocated.equals(jdbc().queryForList("SELECT id,auth_uid,token_version FROM users ORDER BY id")),"Resumption replaced an identity");
            assertEquals(1,flyway("7").migrate().migrationsExecuted);
            assertTrue(allocated.equals(jdbc().queryForList("SELECT id,auth_uid,token_version FROM users ORDER BY id")),"Flyway reapplication replaced an identity");
        }
    }

    @Test void partiallyAllocatedIdentitiesAndNonzeroVersionsAreNeverReset() {
        legacy();
        jdbc().execute("ALTER TABLE users ADD auth_uid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD token_version BIGINT NOT NULL DEFAULT 0");
        String allocated=UUID.randomUUID().toString();
        jdbc().update("UPDATE users SET auth_uid=?,token_version=4 WHERE id=1",allocated);
        flyway("7").migrate();
        assertEquals(allocated,jdbc().queryForObject("SELECT auth_uid FROM users WHERE id=1",String.class));
        assertEquals(4L,jdbc().queryForObject("SELECT token_version FROM users WHERE id=1",Long.class));
        AuthIdentity.requireUid(jdbc().queryForObject("SELECT auth_uid FROM users WHERE id=2",String.class));
        assertEquals(0L,jdbc().queryForObject("SELECT token_version FROM users WHERE id=2",Long.class));
    }

    @Test void constraintsRejectDuplicateMalformedNegativeAndOldCliInsertsWithoutChangingUsers() {
        legacy();flyway("7").migrate();
        var before=jdbc().queryForList("SELECT * FROM users ORDER BY id");
        String uid=jdbc().queryForObject("SELECT auth_uid FROM users WHERE id=1",String.class);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc().update("INSERT INTO users(username,password,role,auth_uid) VALUES('duplicate','unused','ROLE_USER',?)",uid));
        for(String invalid:List.of("old-user","A"+uid.substring(1),uid.substring(0,14)+"1"+uid.substring(15)))
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc().update("UPDATE users SET auth_uid=? WHERE id=1",invalid));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc().update("UPDATE users SET token_version=-1 WHERE id=1"));
        var oldCli = assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                ()->jdbc().update("INSERT INTO users(username,password,role) VALUES('old-cli','unused','ROLE_ADMIN')"));
        assertEquals(1364, oldCli.getSQLException().getErrorCode()); // Required auth_uid has no default.
        assertTrue(before.equals(jdbc().queryForList("SELECT * FROM users ORDER BY id")),"Rejected write changed users");
    }

    @Test void invalidPartialDataFailsClosedAndDoesNotAutomaticallyRepairHistory() {
        legacy();
        jdbc().execute("ALTER TABLE users ADD auth_uid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        jdbc().update("UPDATE users SET auth_uid='invalid-identity' WHERE id=1");
        assertThrows(org.flywaydb.core.api.FlywayException.class,()->flyway("7").migrate());
        assertEquals("invalid-identity",jdbc().queryForObject("SELECT auth_uid FROM users WHERE id=1",String.class));
        assertEquals(2,jdbc().queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(1,jdbc().queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=0",Integer.class));
        assertThrows(org.flywaydb.core.api.FlywayException.class,()->flyway("7").migrate());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"wrong-default","fake-unique","fake-uid-check","fake-version-check"})
    void unexpectedPartialDefaultsOrNamedConstraintsAreNeverTrusted(String scenario) {
        legacy();
        jdbc().execute("ALTER TABLE users ADD auth_uid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, "
                + "ADD token_version BIGINT NOT NULL DEFAULT "+(scenario.equals("wrong-default")?"5":"0"));
        switch(scenario) {
            case "fake-unique" -> jdbc().execute("ALTER TABLE users ADD CONSTRAINT uk_users_auth_uid UNIQUE(username)");
            case "fake-uid-check" -> jdbc().execute("ALTER TABLE users ADD CONSTRAINT ck_users_auth_uid CHECK(1=1)");
            case "fake-version-check" -> jdbc().execute("ALTER TABLE users ADD CONSTRAINT ck_users_token_version CHECK(1=1)");
            default -> { }
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class,()->flyway("7").migrate());
        assertEquals(2,jdbc().queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(0,jdbc().queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1",Integer.class));
    }
}
