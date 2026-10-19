package com.example.demo.security;

import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
@Import(DatabaseTimeoutIT.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseTimeoutIT {
    private static final String SECRET = randomValue();
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("database_timeout").withUsername("inventory_app").withPassword(randomValue())
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.datasource.driver-class-name", database::getDriverClassName);
        registry.add("app.login.rate-limit.trusted-proxies", () -> "127.0.0.1");
        registry.add("JWT_SECRET", () -> SECRET);
    }
    @LocalServerPort int port;
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String token;

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean TimeoutProbe timeoutProbe(PlatformTransactionManager manager, JdbcTemplate jdbc) { return new TimeoutProbe(manager,jdbc); }
    }
    // Fault injection exists only in test sources, never the production image/API.
    @RestController
    static class TimeoutProbe {
        private final PlatformTransactionManager manager;
        private final JdbcTemplate jdbc;
        TimeoutProbe(PlatformTransactionManager manager,JdbcTemplate jdbc) { this.manager=manager;this.jdbc=jdbc; }
        @PostMapping("/api/v1/me/database-timeout-probe")
        Map<String,Boolean> slowTransaction() {
            return new TransactionTemplate(manager).execute(status -> {
                jdbc.update("UPDATE products SET price=777 WHERE id=1");
                jdbc.queryForObject("SELECT SLEEP(15)",Integer.class);
                return Map.of("completed",true);
            });
        }
    }

    @BeforeEach
    void seed() {
        for (String table : List.of("inventory_movements", "order_items", "orders", "products", "users")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO users(auth_uid,id,username,password,role) VALUES(UUID_v4(),1,'timeout_user','unused-test-hash','ROLE_USER')");
        jdbc.update("INSERT INTO products(id,name,price,stock,version) VALUES(1,'first',100,10,0),(2,'second',200,10,0)");
        token = com.example.demo.TestAuth.token(jwt, jdbc, "timeout_user");
    }
    @AfterEach
    void credentialsNeverEnterProductionConsole(CapturedOutput output) {
        for (String value : List.of(SECRET,database.getPassword(),database.getJdbcUrl(),"timeout_user","unused-test-hash",token))
            assertFalse(output.getAll().contains(value),"Sensitive authentication/DB value in console");
    }

    @Test
    void effectiveProductionBudgetsAreExplicitAndPoolIsNotExpanded() throws Exception {
        var pool = (HikariDataSource) source;
        long lock = jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Long.class);
        int tx = ((AbstractPlatformTransactionManager) transactions).getDefaultTimeout();
        System.out.printf("AUD-11 effective: pool=%d acquireMs=%d validationMs=%d lockSeconds=%d transactionSeconds=%d%n",
                pool.getMaximumPoolSize(), pool.getConnectionTimeout(), pool.getValidationTimeout(), lock, tx);
        assertEquals(10, pool.getMaximumPoolSize());
        assertEquals(5000, pool.getConnectionTimeout());
        assertTrue(pool.getValidationTimeout() < pool.getConnectionTimeout());
        assertEquals(5, lock);
        assertEquals(10, tx);
        assertEquals("3000",pool.getDataSourceProperties().getProperty("connectTimeout"));
        try(var connection=pool.getConnection()) { assertEquals(30000,connection.getNetworkTimeout()); }
    }

    @Test
    void exhaustedPoolReturns503AndRecoversWithoutDiscardingJwt() throws Exception {
        var pool = (HikariDataSource) source;
        var held = new ArrayList<Connection>();
        var executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending = null;
        HttpResponse<String> response = null;
        long start = System.nanoTime();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) held.add(source.getConnection());
            pending = executor.submit(() -> send("/api/v1/me/orders", null, null));
            await(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() > 0);
            try { response = pending.get(7, TimeUnit.SECONDS); }
            catch (TimeoutException expectedBeforeFix) { /* Release leases before reporting the measured failure. */ }
        } finally {
            for (Connection connection : held) connection.close();
            if (pending != null && response == null) response = pending.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.printf("AUD-11 saturated pool: status=%d elapsedMs=%d%n", response.statusCode(), elapsed);
        problem(response, 503, "SERVICE_UNAVAILABLE");
        assertTrue(elapsed < 7000, "Acquisition budget must be enforced without a larger pool");
        assertEquals(200, send("/api/v1/me/orders", null, null).statusCode());
        await(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0);
    }

    @RepeatedTest(value = 3, name = "row timeout {currentRepetition}/{totalRepetitions}")
    void productLockTimeoutReturns409RollsBackAndSameKeyCanRetry() throws Exception {
        RowLockSample beforeWait = rowLockSample();
        assertEquals(0, beforeWait.currentWaits(), "The disposable DB must have no unrelated wait");
        var workerStart = new AtomicLong();
        var workerEnd = new AtomicLong();
        String before = snapshot();
        String key = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        HttpResponse<String> response = null;
        Future<HttpResponse<String>> pending = null;
        long start = System.nanoTime();
        long blockedAt = 0;
        long responseAt = 0;
        long wallStart=System.currentTimeMillis();
        BigDecimal dbWallStart=jdbc.queryForObject("SELECT UNIX_TIMESTAMP(SYSDATE(6))",BigDecimal.class);
        try (var blocker = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT id FROM products WHERE id=2 FOR UPDATE")) {
                assertTrue(rows.next());
            }
            try {
                pending = executor.submit(() -> {
                    workerStart.set(System.nanoTime());
                    try { return send("/api/v1/me/orders", order(), key); }
                    finally { workerEnd.set(System.nanoTime()); }
                });
                await(() -> rootNumber("SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS") > 0);
                blockedAt = System.nanoTime();
                try {
                    response = pending.get(8, TimeUnit.SECONDS);
                    responseAt = System.nanoTime();
                    if (response.statusCode() == 409) {
                        // The failed application transaction must release the earlier product lock
                        // while this independent transaction still holds the conflicting product.
                        try (var statement = blocker.createStatement();
                             var rows = statement.executeQuery("SELECT id FROM products WHERE id=1 FOR UPDATE NOWAIT")) {
                            assertTrue(rows.next());
                        }
                    }
                } catch (TimeoutException missingBoundedResponse) { /* Always release the fixture lock before reporting failure. */ }
            } finally { blocker.rollback(); }
        } finally {
            if (pending != null && response == null) response = pending.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        long observedWait = responseAt > blockedAt ? TimeUnit.NANOSECONDS.toMillis(responseAt - blockedAt) : -1;
        RowLockSample afterWait = rowLockSample();
        long dbWaitMs = afterWait.millis() - beforeWait.millis();
        long dbWaits = afterWait.waits() - beforeWait.waits();
        BigDecimal dbWallEnd=jdbc.queryForObject("SELECT UNIX_TIMESTAMP(SYSDATE(6))",BigDecimal.class);
        System.out.printf("AUD-11 lock clocks: os=%s java=%s cpus=%d heapUsedMiB=%d hostMonoMs=%d hostWallMs=%d dbWallMs=%d%n",
                System.getProperty("os.name"),System.getProperty("java.version"),Runtime.getRuntime().availableProcessors(),
                (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576,
                elapsed,System.currentTimeMillis()-wallStart,
                dbWallEnd.subtract(dbWallStart).multiply(BigDecimal.valueOf(1000)).longValue());
        System.out.printf("AUD-11 product row wait: status=%d elapsedMs=%d observedWaitMs=%d dbWaitMs=%d dbWaits=%d workerHttpMs=%d waiterResumeMs=%d%n",
                response.statusCode(),elapsed,observedWait,dbWaitMs,dbWaits,
                TimeUnit.NANOSECONDS.toMillis(workerEnd.get()-workerStart.get()),
                responseAt >= workerEnd.get() ? TimeUnit.NANOSECONDS.toMillis(responseAt-workerEnd.get()) : -1);
        problem(response, 409, "CONCURRENT_UPDATE");
        assertTrue(responseAt > blockedAt, "The bounded HTTP response must arrive before releasing the blocker");
        assertEquals(1, dbWaits, "The isolated fixture must measure exactly one row lock wait");
        assertEquals(0, afterWait.currentWaits(), "The timed-out request must leave no pending DB wait");
        // Native DB timing shares the server's timeout clock; host HTTP/VM scheduling does not.
        // Retain the original upper bound and also reject NOWAIT or a mistakenly shortened timeout.
        assertTrue(dbWaitMs >= 4500 && dbWaitMs < 6500,
                "Native InnoDB wait must enforce the five-second budget (4500 <= ms < 6500)");
        assertTrue(before.equals(snapshot()), "Failed transaction must leave all business rows unchanged");
        assertEquals(200, send("/api/v1/me/orders", order(), key).statusCode());
        String after = snapshot();
        assertEquals(200, send("/api/v1/me/orders", order(), key).statusCode());
        assertTrue(after.equals(snapshot()), "Retry must not double-process");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class));
        assertEquals(18, jdbc.queryForObject("SELECT SUM(stock) FROM products", Integer.class));
        await(() -> ((HikariDataSource) source).getHikariPoolMXBean().getActiveConnections() == 0);
    }

    @Test
    void transactionDeadlineCancelsQueryReturns503AndRollsBackEarlierWrite() throws Exception {
        String before=snapshot();
        long started=System.nanoTime();
        var response=send("/api/v1/me/database-timeout-probe",Map.of(),null);
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        System.out.printf("AUD-11 transaction/query deadline: status=%d elapsedMs=%d%n",response.statusCode(),elapsed);
        problem(response,503,"SERVICE_UNAVAILABLE");
        assertTrue(elapsed>=8000 && elapsed<13500,"Transaction budget must bound the real server query");
        assertTrue(before.equals(snapshot()),"A query timeout must roll back the earlier write, not just its own statement");
        await(() -> rootNumber("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='inventory_app' AND INFO LIKE '%SLEEP(15)%'")==0);
        await(() -> ((HikariDataSource)source).getHikariPoolMXBean().getActiveConnections()==0);
        assertEquals(200,send("/api/v1/me/orders",null,null).statusCode());
    }

    @Test
    void killedConnectionWithUnclassifiedRollbackFailureKeepsSafe500AndDataRollsBack() throws Exception {
        String before=snapshot();
        var executor=Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending=null;
        try {
            pending=executor.submit(() -> send("/api/v1/me/database-timeout-probe",Map.of(),null));
            String query="SELECT COALESCE(MAX(ID),0) FROM information_schema.PROCESSLIST WHERE INFO LIKE '%SLEEP(15)%' AND USER='inventory_app'";
            await(() -> rootNumber(query)>0);
            long connection=rootNumber(query);
            assertTrue(connection>0);
            rootCommand("KILL "+connection);
            var response=pending.get(5,TimeUnit.SECONDS);
            // The JDBC rollback itself fails after KILL; Hibernate exposes a causeless SQLException.
            // Do not guess that this unclassified transaction-end failure is transient (AUD-10).
            problem(response,500,"SYSTEM_ERROR");
            System.out.println("AUD-11 connection terminated during transaction: safe500, full rollback, subsequent JWT request recovers");
            assertTrue(before.equals(snapshot()),"A dropped connection must roll back the full transaction");
            await(() -> ((HikariDataSource)source).getHikariPoolMXBean().getActiveConnections()==0);
            assertEquals(200,send("/api/v1/me/orders",null,null).statusCode());
        } finally {
            // Even a failed observation must let the server finish/rollback before the next test seeds data.
            try { if(pending!=null) pending.get(20,TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        }
    }

    @Test
    void borrowedConnectionCannotWaitForeverWhenDatabaseStopsResponding() throws Exception {
        String before=snapshot();
        try(var connection=source.getConnection()) {
            System.out.printf("AUD-11 driver socketTimeoutMs=%d%n",connection.getNetworkTimeout());
        }
        var executor=Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending=null;
        HttpResponse<String> response=null;
        boolean paused=false;
        long waitingAt=0;
        try {
            pending=executor.submit(() -> send("/api/v1/me/database-timeout-probe",Map.of(),null));
            await(() -> rootNumber("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='inventory_app' AND INFO LIKE '%SLEEP(15)%'")>0);
            database.getDockerClient().pauseContainerCmd(database.getContainerId()).exec();paused=true;
            waitingAt=System.nanoTime();
            try { response=pending.get(34,TimeUnit.SECONDS); }
            catch(TimeoutException expectedWithoutSocketBudget) { /* Record unbounded old behavior; always restore this fixture. */ }
        } finally {
            if(paused) database.getDockerClient().unpauseContainerCmd(database.getContainerId()).exec();
            try { if(pending!=null && response==null) response=pending.get(10,TimeUnit.SECONDS); }
            finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        }
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-waitingAt);
        System.out.printf("AUD-11 nonresponsive borrowed connection: status=%d elapsedMs=%d%n",response.statusCode(),elapsed);
        assertTrue(elapsed<33000,"A transaction query timeout alone cannot interrupt a frozen server; require a finite driver read budget");
        assertTrue(response.statusCode()==503 || response.statusCode()==500,"Connection loss/rollback failure is a server error, never authentication denial or success");
        problem(response,response.statusCode(),response.statusCode()==503?"SERVICE_UNAVAILABLE":"SYSTEM_ERROR");
        assertTrue(before.equals(snapshot()),"Disconnected transaction must not commit its earlier write");
        try(var connection=source.getConnection()) { assertEquals(30000,connection.getNetworkTimeout()); }
        assertEquals(200,send("/api/v1/me/orders",null,null).statusCode());
        await(() -> ((HikariDataSource)source).getHikariPoolMXBean().getActiveConnections()==0);
    }

    @Test
    void newConnectionHandshakeHasItsOwnFiniteBudget() throws Exception {
        var properties=new Properties();properties.putAll(((HikariDataSource)source).getDataSourceProperties());
        properties.setProperty("user",database.getUsername());properties.setProperty("password",database.getPassword());
        var executor=Executors.newSingleThreadExecutor();
        Future<Boolean> pending=null;
        Boolean timedOut=null;
        long started=0;
        boolean paused=false;
        try {
            database.getDockerClient().pauseContainerCmd(database.getContainerId()).exec();paused=true;
            started=System.nanoTime();
            pending=executor.submit(() -> {
                try(var ignored=DriverManager.getConnection(database.getJdbcUrl(),properties)) { return false; }
                catch(SQLException expected) { return true; }
            });
            try { timedOut=pending.get(6,TimeUnit.SECONDS); }
            catch(TimeoutException ignored) { /* Always restore the disposable server before reporting failure. */ }
        } finally {
            if(paused) database.getDockerClient().unpauseContainerCmd(database.getContainerId()).exec();
            try { if(pending!=null && timedOut==null) timedOut=pending.get(10,TimeUnit.SECONDS); }
            finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        }
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        System.out.printf("AUD-11 new connection handshake: failed=%s elapsedMs=%d%n",timedOut,elapsed);
        assertEquals(Boolean.TRUE,timedOut);assertTrue(elapsed<5500,"Physical connect/handshake must not use an unbounded read budget");
        assertEquals(200,send("/api/v1/me/orders",null,null).statusCode());
    }

    private Map<String,Object> order() { return Map.of("items", List.of(Map.of("productId",1,"quantity",1),Map.of("productId",2,"quantity",1))); }
    private String snapshot() { return jdbc.queryForList("SELECT id,price,stock,version FROM products ORDER BY id") + ":" +
            jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM orders)+(SELECT COUNT(*) FROM order_items)+(SELECT COUNT(*) FROM inventory_movements)", Long.class); }

    private record RowLockSample(long millis, long waits, long currentWaits) { }

    private RowLockSample rowLockSample() {
        Map<String, Long> values = jdbc.query("SELECT VARIABLE_NAME, VARIABLE_VALUE FROM information_schema.GLOBAL_STATUS "
                + "WHERE VARIABLE_NAME IN ('INNODB_ROW_LOCK_TIME','INNODB_ROW_LOCK_WAITS','INNODB_ROW_LOCK_CURRENT_WAITS')", rs -> {
            Map<String, Long> result = new HashMap<>();
            while (rs.next()) result.put(rs.getString(1).toUpperCase(Locale.ROOT), rs.getLong(2));
            return result;
        });
        assertEquals(Set.of("INNODB_ROW_LOCK_TIME", "INNODB_ROW_LOCK_WAITS", "INNODB_ROW_LOCK_CURRENT_WAITS"), values.keySet());
        return new RowLockSample(values.get("INNODB_ROW_LOCK_TIME"), values.get("INNODB_ROW_LOCK_WAITS"),
                values.get("INNODB_ROW_LOCK_CURRENT_WAITS"));
    }
    private HttpResponse<String> send(String path, Object data, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(40))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        if (key != null) builder.header("Idempotency-Key",key);
        if (data == null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode());
        var body=json.readTree(response.body());
        assertEquals(6,body.size()); assertEquals(code,body.path("code").asText());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertFalse(response.body().contains(token));
    }
    private static long rootNumber(String sql) {
        return Long.parseLong(rootCommand(sql));
    }
    private static String rootCommand(String sql) {
        try {
            var result=database.execInContainer("sh","-c","MYSQL_PWD=\"${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}\" mariadb -uroot -N -B -e \"$1\"","db-probe",sql);
            assertEquals(0,result.getExitCode(),"Disposable DB observation failed");
            return result.getStdout().trim();
        } catch (Exception ex) { throw new AssertionError("Disposable DB observation failed",ex); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        do { if(condition.getAsBoolean())return; Thread.sleep(20); } while(System.nanoTime()<deadline);
        fail("Expected bounded DB state was not observed");
    }
    private static String randomValue() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
}
