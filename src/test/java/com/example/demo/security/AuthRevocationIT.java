package com.example.demo.security;

import com.example.demo.TestAuth;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthRevocationTransactions;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AuthRevocationIT {
    private static String random() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
    private static final String SECRET=random(), PASSWORD=random();
    private static final AtomicInteger SOURCES=new AtomicInteger();
    @Container static final MariaDBContainer database=new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("auth_revocation_b1").withUsername("inventory_app").withPassword(random())
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh",0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",database::getJdbcUrl);r.add("spring.datasource.username",database::getUsername);
        r.add("spring.datasource.password",database::getPassword);r.add("spring.datasource.driver-class-name",database::getDriverClassName);
        r.add("app.login.rate-limit.trusted-proxies",()->"127.0.0.1");r.add("JWT_SECRET",()->SECRET);
    }
    @LocalServerPort int port;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entities;
    @Autowired AuthRevocationTransactions revocations;
    @Autowired DataSource source;
    @Autowired AuthMutationRateLimiter mutationLimiter;
    @Autowired LoginRateLimiter loginLimiter;
    @MockitoSpyBean JwtUtil jwt;
    @MockitoSpyBean JwtUserAuthenticator authenticator;
    @MockitoSpyBean PasswordEncoder passwords;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private AuthPrincipal alice,bob,admin;
    private String aliceToken,bobToken,adminToken,hash;

    @BeforeEach void fixture() {
        for(String table:List.of("auth_revocation_audit","inventory_movements","order_items","orders","products","users"))
            jdbc.update("DELETE FROM "+table); // This newly-created container only.
        hash=passwords.encode(PASSWORD);
        for(String name:List.of("revocation_alice","revocation_bob","revocation_admin"))
            users.saveAndFlush(User.builder().username(name).password(hash).role(name.endsWith("admin")?"ROLE_ADMIN":"ROLE_USER").build());
        alice=TestAuth.principal(jdbc,"revocation_alice");bob=TestAuth.principal(jdbc,"revocation_bob");admin=TestAuth.principal(jdbc,"revocation_admin");
        aliceToken=jwt.generateToken(alice.uid(),0);bobToken=jwt.generateToken(bob.uid(),0);adminToken=jwt.generateToken(admin.uid(),0);
        clearInvocations(jwt,authenticator,passwords);
    }

    private String nextSource() { int n=SOURCES.incrementAndGet();return "198.18."+(n/250)+"."+(n%250); }
    private HttpResponse<String> send(String method,String path,String token,Object body,String client) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("X-Forwarded-For",client);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String token) throws Exception {return send("GET","/api/v1/me/orders",token,null,nextSource());}
    private HttpResponse<String> logout(String token) throws Exception {return send("POST","/auth/logout-all",token,null,nextSource());}
    private HttpResponse<String> login() throws Exception {
        return send("POST","/auth/login",null,Map.of("username","revocation_alice","password",PASSWORD),nextSource());
    }
    private void problem(HttpResponse<String> response,int status,String code) throws Exception {
        assertEquals(status,response.statusCode());var body=json.readTree(response.body());
        assertEquals(6,body.size());assertEquals(code,body.path("code").asText());
        assertEquals(status,body.path("status").asInt());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/problem+json"));
        assertNotNull(response.headers().firstValue("X-Request-Id").orElse(null));
        for(String secret:List.of(SECRET,PASSWORD,hash,"revocation_alice","jdbc:"))assertFalse(response.body().contains(secret),"Problem leaked authentication data");
    }
    private long version(AuthPrincipal user) {return jdbc.queryForObject("SELECT token_version FROM users WHERE auth_uid=?",Long.class,user.uid());}
    private int audits() {return jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_audit",Integer.class);}

    @Test void logoutAllRevokesAllDevicesOnlyForTheAuthenticatedUserAndPersistsAfterNewReads(CapturedOutput output) throws Exception {
        String second=jwt.generateToken(alice.uid(),0);
        var response=send("POST","/auth/logout-all",aliceToken,Map.of("uid",bob.uid()),nextSource());
        assertEquals(204,response.statusCode());assertTrue(response.body().isEmpty());
        assertEquals(1,version(alice));assertEquals(0,version(bob));assertEquals(1,audits());
        assertEquals(alice.id(),jdbc.queryForObject("SELECT actor_user_id FROM auth_revocation_audit",Long.class));
        assertEquals(alice.id(),jdbc.queryForObject("SELECT target_user_id FROM auth_revocation_audit",Long.class));
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(),jdbc.queryForObject("SELECT request_id FROM auth_revocation_audit",String.class));
        problem(get(aliceToken),401,"UNAUTHENTICATED");problem(get(second),401,"UNAUTHENTICATED");
        problem(logout(aliceToken),401,"UNAUTHENTICATED");assertEquals(1,audits());
        assertEquals(200,get(bobToken).statusCode());assertEquals(200,send("GET","/api/v1/admin/orders",adminToken,null,nextSource()).statusCode());
        var relogin=login();assertEquals(200,relogin.statusCode());String fresh=json.readTree(relogin.body()).path("token").asText();
        assertEquals(new JwtUtil.VerifiedJwt(alice.uid(),1),jwt.verify(fresh));assertEquals(200,get(fresh).statusCode());
        assertEquals(1,version(alice));assertTrue(hash.equals(users.findByAuthUid(alice.uid()).orElseThrow().getPassword()),"Credential changed");
        for(String value:List.of(SECRET,PASSWORD,hash,aliceToken,second,fresh,"revocation_alice",database.getPassword(),database.getJdbcUrl()))
            assertFalse(output.getAll().contains(value),"Authentication data entered logs");
        assertTrue(output.getAll().contains("auth.logout_all.succeeded"));
        assertEquals(0,mutationLimiter.inFlightCount());
    }

    @Test void legacyUsernameJwtIsRejectedBeforeAuthenticationDatabaseLookup() throws Exception {
        String legacy=Jwts.builder().subject("revocation_alice").issuer("inventory-management-system")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+3600000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        problem(get(legacy),401,"UNAUTHENTICATED");verifyNoInteractions(authenticator);
        assertEquals(0,version(alice));assertEquals(0,audits());
        assertEquals(200,get(aliceToken).statusCode());
    }

    @ParameterizedTest @ValueSource(strings={"missing-uid","missing-version","uid-mismatch","uppercase-uid","numeric-version","negative-version","leading-zero","overflow-version","missing-iat"})
    void signedInvalidClaimsAre401BeforeDatabaseLookup(String scenario) throws Exception {
        Object uid=scenario.equals("missing-uid")?null:scenario.equals("uid-mismatch")?bob.uid():scenario.equals("uppercase-uid")?"A"+alice.uid().substring(1):alice.uid();
        Object generation=switch(scenario){case "missing-version"->null;case "numeric-version"->0;case "negative-version"->"-1";case "leading-zero"->"00";case "overflow-version"->"9223372036854775808";default->"0";};
        var builder=Jwts.builder().subject(alice.uid()).claim("uid",uid).claim("tokenVersion",generation)
                .issuer("inventory-management-system").expiration(new Date(System.currentTimeMillis()+3600000));
        if(!scenario.equals("missing-iat"))builder.issuedAt(new Date());
        String bad=builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        problem(logout(bad),401,"UNAUTHENTICATED");verifyNoInteractions(authenticator);assertEquals(0,audits());assertEquals(0,version(alice));
        assertEquals(200,get(aliceToken).statusCode());
    }

    @Test void authenticationPerformsOneParseAndOneCommittedIdentityRead() throws Exception {
        assertEquals(200,get(aliceToken).statusCode());
        verify(jwt,times(1)).verify(aliceToken);
        verify(authenticator,times(1)).authenticate(new JwtUtil.VerifiedJwt(alice.uid(),0));
        verifyNoMoreInteractions(jwt,authenticator);
    }

    @Test void twoAlreadyAuthenticatedLogoutRequestsHaveExactlyOneSuccessAndAudit() throws Exception {
        var admitted=new CountDownLatch(2);var release=new CountDownLatch(1);
        doAnswer(call->{var principal=call.callRealMethod();admitted.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));return principal;})
                .when(authenticator).authenticate(new JwtUtil.VerifiedJwt(alice.uid(),0));
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->logout(aliceToken));var second=workers.submit(()->logout(aliceToken));
            assertTrue(admitted.await(8,TimeUnit.SECONDS));release.countDown();
            var statuses=new ArrayList<>(List.of(first.get(12,TimeUnit.SECONDS).statusCode(),second.get(12,TimeUnit.SECONDS).statusCode()));
            statuses.sort(Integer::compareTo);assertEquals(List.of(204,401),statuses);
            assertEquals(1,version(alice));assertEquals(1,audits());assertEquals(0,mutationLimiter.inFlightCount());
        }finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void authenticationUsesCommittedStateAcrossARevocationCommitEvenInsideAnOldBusinessSnapshot() throws Exception {
        var written=new CountDownLatch(1);var commit=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        try {
            var pending=worker.submit(()->new TransactionTemplate(manager).execute(status->{
                revocations.logoutAll(alice,UUID.randomUUID().toString());written.countDown();
                try {assertTrue(commit.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return null;
            }));
            assertTrue(written.await(8,TimeUnit.SECONDS));
            var oldSnapshot=new TransactionTemplate(manager);oldSnapshot.setReadOnly(true);oldSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            oldSnapshot.execute(status->{
                var cached=users.findByAuthUid(alice.uid()).orElseThrow();assertEquals(0,cached.getTokenVersion());
                assertEquals(0,authenticator.authenticate(new JwtUtil.VerifiedJwt(alice.uid(),0)).tokenVersion());
                assertEquals(0,audits());commit.countDown();
                try {pending.get(8,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
                assertEquals(0,cached.getTokenVersion());
                assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                        ()->authenticator.authenticate(new JwtUtil.VerifiedJwt(alice.uid(),0)));
                return null;
            });
            problem(get(aliceToken),401,"UNAUTHENTICATED");assertEquals(1,audits());assertEquals(1,version(alice));
        }finally {commit.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void rollbackRestoresVersionAuditAndAuthentication() {
        var tx=new TransactionTemplate(manager);
        tx.execute(status->{revocations.logoutAll(alice,UUID.randomUUID().toString());status.setRollbackOnly();return null;});
        assertEquals(0,version(alice));assertEquals(0,audits());
        assertEquals(0,authenticator.authenticate(new JwtUtil.VerifiedJwt(alice.uid(),0)).tokenVersion());
    }

    @Test void auditInsertionFailureRollsBackTheIncrementAndKeepsTheSameJwtUsable() throws Exception {
        // Inject after the real MariaDB version UPDATE, without granting the app TRIGGER/admin privileges.
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Injected audit rejection"))
                .when(jdbc).update(eq("INSERT INTO auth_revocation_audit(actor_user_id,target_user_id,action,result,created_at,request_id) VALUES (?,?,'LOGOUT_ALL','SUCCEEDED',?,?)"),
                        eq(alice.id()),eq(alice.id()),any(java.sql.Timestamp.class),anyString());
        problem(logout(aliceToken),500,"SYSTEM_ERROR");assertEquals(0,version(alice));assertEquals(0,audits());
        assertEquals(200,get(aliceToken).statusCode());assertEquals(0,mutationLimiter.inFlightCount());
    }

    @Test void loginNeverSignsANewGenerationFromPasswordVerificationThatPrecededLogout() throws Exception {
        var verified=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"BCrypt ran under a DB transaction");
            boolean match=(boolean)call.callRealMethod();verified.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));return match;
        }).when(passwords).matches(PASSWORD,hash);
        try {
            var pending=worker.submit(this::login);assertTrue(verified.await(8,TimeUnit.SECONDS));
            assertEquals(204,logout(aliceToken).statusCode());release.countDown();
            problem(pending.get(12,TimeUnit.SECONDS),401,"INVALID_CREDENTIALS");
            verify(jwt,never()).generateToken(anyString(),anyLong());assertEquals(1,version(alice));assertEquals(1,audits());
        }finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void usernameReassignmentCannotMakeAnOldUuidTokenReferToANewAccount() throws Exception {
        users.deleteById(alice.id());users.flush();
        var replacement=users.saveAndFlush(User.builder().username("revocation_alice").password(hash).role("ROLE_ADMIN").build());
        assertNotEquals(alice.uid(),replacement.getAuthUid());assertNotEquals(alice.id(),replacement.getId());
        problem(get(aliceToken),401,"UNAUTHENTICATED");
        var replacementToken=jwt.generateToken(replacement.getAuthUid(),0);
        assertEquals(200,send("GET","/api/v1/admin/orders",replacementToken,null,nextSource()).statusCode());
        var old=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(alice,null,alice.authorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(old);
        try {assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                ()->new CurrentUser(users).requireUser());}finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    @Test void overflowFailsClosedWithoutChangingVersionOrAudit() throws Exception {
        jdbc.update("UPDATE users SET token_version=? WHERE auth_uid=?",Long.MAX_VALUE,alice.uid());
        String exhausted=Jwts.builder().subject(alice.uid()).claim("uid",alice.uid()).claim("tokenVersion",Long.toString(Long.MAX_VALUE))
                .issuer("inventory-management-system").issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+3600000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        problem(logout(exhausted),500,"SYSTEM_ERROR");assertEquals(Long.MAX_VALUE,version(alice));assertEquals(0,audits());
        problem(login(),500,"SYSTEM_ERROR");
    }

    @Test void mutationSourceBudgetAndLoginRegistrationBudgetsAreIndependent() throws Exception {
        String client=nextSource();
        for(int n=0;n<5;n++) {
            String token=jwt.generateToken(alice.uid(),n);
            assertEquals(204,send("POST","/auth/logout-all",token,null,client).statusCode());
        }
        String current=jwt.generateToken(alice.uid(),5);
        var limited=send("POST","/auth/logout-all",current,null,client);problem(limited,429,"AUTH_MUTATION_RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())>0);
        assertEquals(200,send("POST","/auth/login",null,Map.of("username","revocation_alice","password",PASSWORD),client).statusCode());
        assertEquals(200,send("POST","/auth/register",null,Map.of("username","new_revocation_user","password",PASSWORD),client).statusCode());
        assertEquals(204,send("POST","/auth/logout-all",current,null,nextSource()).statusCode());
        assertEquals(6,version(alice));assertEquals(6,audits());
        assertEquals(0,mutationLimiter.inFlightCount());assertEquals(0,loginLimiter.inFlightCount());
    }

    @Test void sourceForgeryFailsClosedAndLogoutIsNeverPublic() throws Exception {
        problem(logout(null),401,"UNAUTHENTICATED");
        var response=send("POST","/auth/logout-all",aliceToken,null,"198.18.1.2, 198.18.1.3");
        problem(response,503,"AUTH_MUTATION_SOURCE_UNAVAILABLE");assertTrue(response.headers().firstValue("Retry-After").isEmpty());
        assertEquals(0,audits());assertEquals(0,version(alice));assertEquals(200,get(aliceToken).statusCode());
        assertEquals(10,((HikariDataSource)source).getMaximumPoolSize());
        assertEquals(5000,((HikariDataSource)source).getConnectionTimeout());
    }

    @ParameterizedTest @ValueSource(strings={"login","logout"})
    void authenticationRowLockTimeoutIs503WithoutAFalseCredentialFailureOrAudit(String operation) throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
        try {
            var holder=worker.submit(()->new TransactionTemplate(manager).execute(status->{
                users.findByAuthUidForUpdate(alice.uid()).orElseThrow();held.countDown();
                try {assertTrue(release.await(9,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return null;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            problem(operation.equals("login")?login():logout(aliceToken),503,"SERVICE_UNAVAILABLE");
            release.countDown();holder.get(5,TimeUnit.SECONDS);
            assertEquals(0,version(alice));assertEquals(0,audits());assertEquals(0,mutationLimiter.inFlightCount());
            assertEquals(200,get(aliceToken).statusCode());
        }finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void registerCannotChooseAnAuthenticationIdentityOrGeneration() throws Exception {
        var response=send("POST","/auth/register",null,Map.of("username","new_identity_user","password",PASSWORD,
                "authUid",alice.uid(),"tokenVersion",55,"role","ROLE_ADMIN"),nextSource());
        assertEquals(200,response.statusCode());var user=users.findByUsername("new_identity_user").orElseThrow();
        AuthIdentity.requireUid(user.getAuthUid());assertNotEquals(alice.uid(),user.getAuthUid());
        assertEquals(0,user.getTokenVersion());assertEquals("ROLE_USER",user.getRole());
    }

    @Test void orderAuthenticatedBeforeLogoutCommitMayFinishAndReloginRetryDoesNotDuplicateIt() throws Exception {
        var product=send("POST","/products",adminToken,Map.of("name","revocation-order","price",100,"stock",2),nextSource());
        assertEquals(200,product.statusCode());long productId=json.readTree(product.body()).path("id").asLong();
        var written=new CountDownLatch(1);var commit=new CountDownLatch(1);var orderLock=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        doAnswer(call->{orderLock.countDown();return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);})
                .when(users).findByIdForUpdate(alice.id());
        Object body=Map.of("items",List.of(Map.of("productId",productId,"quantity",1)));
        try {
            var mutation=workers.submit(()->new TransactionTemplate(manager).execute(status->{
                revocations.logoutAll(alice,UUID.randomUUID().toString());written.countDown();
                try {assertTrue(commit.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return null;
            }));
            assertTrue(written.await(5,TimeUnit.SECONDS));
            var order=workers.submit(()->createOrder(aliceToken,body));
            assertTrue(orderLock.await(5,TimeUnit.SECONDS)); // Already passed JWT authentication and entered its own business Tx.
            commit.countDown();mutation.get(5,TimeUnit.SECONDS);
            var response=order.get(8,TimeUnit.SECONDS);assertEquals(200,response.statusCode());
            long id=json.readTree(response.body()).path("id").asLong();
            problem(get(aliceToken),401,"UNAUTHENTICATED");
            var relogin=login();assertEquals(200,relogin.statusCode());String fresh=json.readTree(relogin.body()).path("token").asText();
            var retry=createOrder(fresh,body);assertEquals(200,retry.statusCode());assertEquals(id,json.readTree(retry.body()).path("id").asLong());
            assertEquals(1,jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,productId));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_items",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements",Integer.class));
            assertEquals(alice.id(),jdbc.queryForObject("SELECT customer_id FROM orders",Long.class));
            assertEquals(1,version(alice));assertEquals(1,audits());
        }finally {commit.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    private HttpResponse<String> createOrder(String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/orders"))
                .timeout(Duration.ofSeconds(15)).header("X-Forwarded-For",nextSource())
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .header("Idempotency-Key","b1-order-across-generation")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
