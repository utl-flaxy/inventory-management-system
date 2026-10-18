package com.example.demo.security;

import com.example.demo.TestApplicationLogs;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.MDC;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Testcontainers
@Import(JwtFilterRegistrationIT.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtFilterRegistrationIT {
    private static final String SECRET = randomValue();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final AtomicInteger CLIENT_SEQUENCE=new AtomicInteger(10);
    @Container static final MariaDBContainer database = new MariaDBContainer("mariadb:12.2.2")
            .withDatabaseName("jwt_routing").withUsername("inventory_app").withPassword(randomValue())
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/db-init/01-app-privileges.sh",0755),
                    "/docker-entrypoint-initdb.d/01-app-privileges.sh");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",database::getJdbcUrl);
        registry.add("spring.datasource.username",database::getUsername);
        registry.add("spring.datasource.password",database::getPassword);
        registry.add("spring.datasource.driver-class-name",database::getDriverClassName);
        registry.add("app.login.rate-limit.trusted-proxies",() -> "127.0.0.1");
        registry.add("JWT_SECRET",() -> SECRET);
    }
    @LocalServerPort int port;
    @Autowired ServletContext servlet;
    @Autowired SecurityFilterChain security;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired ProbeRecorder recorder;
    @MockitoSpyBean JwtAuthenticationFilter filter;
    @MockitoSpyBean JwtUtil jwt;
    @MockitoSpyBean UserDetailsService users;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<String> secrets=new ArrayList<>();
    private String userToken,adminToken;
    private String clientAddress;
    private int lastRejectedEvents,lastServerFailureEvents;

    @TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration {
        @Bean ProbeRecorder recorder() { return new ProbeRecorder(); }
        @Bean ProbeController probe(ProbeRecorder recorder) { return new ProbeController(recorder); }
    }
    static class ProbeRecorder {
        final List<String> trace=new CopyOnWriteArrayList<>();
    }
    @RestController
    static class ProbeController {
        private final ProbeRecorder recorder;
        ProbeController(ProbeRecorder recorder) { this.recorder=recorder; }
        @GetMapping({"/api/v1/me/filter-probe","/api/v1/admin/filter-probe",
                "/authority/filter-probe","/authentication/filter-probe","/authz/filter-probe","/auth/filter-probe"})
        Map<String,Boolean> probe() { recorder.trace.add("controller");return Map.of("ok",true); }
    }

    @BeforeEach
    void seedAndObserve() throws Exception {
        clientAddress="198.51.100."+CLIENT_SEQUENCE.incrementAndGet();
        jdbc.update("DELETE FROM users");
        String hash=encoder.encode(PASSWORD);
        jdbc.update("INSERT INTO users(id,username,password,role) VALUES(1,'routing_user',?,'ROLE_USER'),(2,'routing_admin',?,'ROLE_ADMIN')",hash,hash);
        secrets.addAll(List.of(SECRET,PASSWORD,hash,database.getPassword(),database.getJdbcUrl(),"routing_user","routing_admin"));
        userToken=jwt.generateToken("routing_user");adminToken=jwt.generateToken("routing_admin");
        secrets.addAll(List.of(userToken,adminToken));
        recorder.trace.clear();
        clearInvocations(filter,jwt,users);
        doAnswer(call -> {
            recorder.trace.add("jwt-entry:"+call.<FilterChain>getArgument(2).getClass().getSimpleName());
            assertNotNull(MDC.get("requestId"),"Correlation must precede JWT");
            return call.callRealMethod();
        }).when(filter).doFilter(any(),any(),any());
        doAnswer(call -> { recorder.trace.add("jwt-internal");return call.callRealMethod(); })
                .when(filter).doFilterInternal(any(),any(),any());
        doAnswer(call -> { recorder.trace.add("parse");return call.callRealMethod(); }).when(jwt).extractUsername(anyString());
        doAnswer(call -> { recorder.trace.add("lookup");return call.callRealMethod(); }).when(users).loadUserByUsername(anyString());
    }
    @AfterEach
    void noConsoleSecrets(CapturedOutput output) {
        for(String secret:secrets) assertFalse(output.getAll().contains(secret),"Sensitive fixture value entered production console");
    }

    @Test
    void realServletRegistrationAndSecurityExecutionHaveOneOwner() throws Exception {
        var names=servlet.getFilterRegistrations().entrySet().stream()
                .filter(e -> e.getValue().getClassName().contains("JwtAuthenticationFilter"))
                .map(Map.Entry::getKey).toList();
        long securityCopies=security.getFilters().stream().filter(f -> f==filter).count();
        var response=send("GET","/api/v1/me/filter-probe",userToken,null);
        System.out.printf("ISSUE-81 registration: servletJwt=%d securityCopies=%d rawEntries=%d internal=%d parse=%d lookup=%d status=%d trace=%s%n",
                names.size(),securityCopies,calls(filter,"doFilter"),calls(filter,"doFilterInternal"),
                calls(jwt,"extractUsername"),calls(users,"loadUserByUsername"),response.statusCode(),recorder.trace);
        assertEquals(200,response.statusCode());
        assertEquals(0,names.size(),"JWT must not be automatically registered in the Servlet container");
        assertEquals(1,securityCopies);
        assertEquals(1,calls(filter,"doFilter"));
        assertEquals(1,calls(filter,"doFilterInternal"));
        assertEquals(1,calls(jwt,"extractUsername"));
        assertEquals(1,calls(users,"loadUserByUsername"));
        List<String> order=security.getFilters().stream().map(f -> f==filter?"JwtAuthenticationFilter":f.getClass().getSimpleName()).toList();
        System.out.println("ISSUE-81 security order: "+order);
        assertTrue(order.indexOf("SecurityContextHolderFilter")<order.indexOf("JwtAuthenticationFilter"));
        assertTrue(order.indexOf("JwtAuthenticationFilter")<order.indexOf("AnonymousAuthenticationFilter"));
        assertTrue(order.indexOf("AnonymousAuthenticationFilter")<order.indexOf("AuthorizationFilter"));
        assertTrue(recorder.trace.indexOf("parse")<recorder.trace.indexOf("lookup"));
        assertTrue(recorder.trace.indexOf("lookup")<recorder.trace.indexOf("controller"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/authority/filter-probe","/authentication/filter-probe","/authz/filter-probe"})
    void similarlyNamedProtectedPathMustProcessValidJwt(String path) throws Exception {
        var response=send("GET",path,userToken,null);
        System.out.printf("ISSUE-81 prefix similarity: status=%d parse=%d lookup=%d%n",
                response.statusCode(),calls(jwt,"extractUsername"),calls(users,"loadUserByUsername"));
        assertEquals(200,response.statusCode());
        assertEquals(1,calls(jwt,"extractUsername"));
        assertEquals(1,calls(users,"loadUserByUsername"));
    }

    @Test
    void futureAuthDescendantIsNotMadePublicByNamespace() throws Exception {
        var response=send("GET","/auth/filter-probe",null,null);
        System.out.printf("ISSUE-81 test-only auth descendant: anonymous status=%d controller=%d%n",
                response.statusCode(),Collections.frequency(recorder.trace,"controller"));
        assertEquals(401,response.statusCode(),"Only the two real public POST auth endpoints may be anonymous");
        assertFalse(recorder.trace.contains("controller"));
    }

    @Test
    void futureAuthDescendantProcessesValidJwt() throws Exception {
        var response=send("GET","/auth/filter-probe",userToken,null);
        assertEquals(200,response.statusCode());
        assertEquals(1,calls(jwt,"extractUsername"));assertEquals(1,calls(users,"loadUserByUsername"));
        assertEquals(1,Collections.frequency(recorder.trace,"controller"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/auth/login","/auth/register"})
    void publicPostsRemainUsableWithoutJwtProcessingEvenWithBearerHeader(String path) throws Exception {
        String username=path.endsWith("register")?"routing_created":"routing_user";
        secrets.add(username);
        doThrow(new IllegalStateException("unreachable-public-parser-canary")).when(jwt).extractUsername(anyString());
        var response=send("POST",path,"malformed-public-bearer",Map.of("username",username,"password",PASSWORD));
        assertEquals(200,response.statusCode());
        var body=json.readTree(response.body());
        if(path.endsWith("register"))assertEquals("ROLE_USER",body.path("role").asText());
        else { assertTrue(body.path("token").isString());secrets.add(body.path("token").asText()); }
        assertFalse(response.body().contains(PASSWORD));
        assertEquals(1,calls(filter,"doFilter"));assertEquals(0,calls(filter,"doFilterInternal"));
        assertEquals(0,calls(jwt,"extractUsername"));assertEquals(0,calls(users,"loadUserByUsername"));
    }

    @Test
    void publicValidationLoginFailureAndThrottleKeepTheirContracts() throws Exception {
        var bad=Map.of("username","routing_user","password","wrong-password");
        problem(send("POST","/auth/login",null,bad),401,"INVALID_CREDENTIALS");
        problem(send("POST","/auth/login",null,Map.of("username","","password","")),400,"VALIDATION_ERROR");
        for(int attempt=1;attempt<5;attempt++)problem(send("POST","/auth/login",null,bad),401,"INVALID_CREDENTIALS");
        var limited=send("POST","/auth/login",null,bad);
        problem(limited,429,"LOGIN_RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())>0);
        assertEquals(0,calls(jwt,"extractUsername"));assertEquals(0,calls(users,"loadUserByUsername"));
    }

    @ParameterizedTest
    @ValueSource(strings={"wrong-secret","expired","wrong-issuer","missing-expiration","malformed"})
    void invalidJwtOnRealServletIsParsedOnceAndRemains401(String scenario) throws Exception {
        String value;
        if(scenario.equals("malformed"))value="malformed-routing-token";
        else {
            String secret=scenario.equals("wrong-secret")?randomValue():SECRET;
            var builder=Jwts.builder().subject("routing_user")
                    .issuer(scenario.equals("wrong-issuer")?"other-system":"inventory-management-system");
            if(!scenario.equals("missing-expiration"))builder.expiration(new Date(System.currentTimeMillis()+(scenario.equals("expired")?-3600000:3600000)));
            value=builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        }
        secrets.add(value);
        problem(send("GET","/api/v1/me/filter-probe",value,null),401,"UNAUTHENTICATED");
        assertEquals(1,calls(filter,"doFilter"));assertEquals(1,calls(filter,"doFilterInternal"));
        assertEquals(1,calls(jwt,"extractUsername"));assertEquals(0,calls(users,"loadUserByUsername"));
        assertEquals(1,lastRejectedEvents);assertFalse(recorder.trace.contains("controller"));
    }

    @Test
    void roleBoundariesAndSecurityContextCleanupSurviveRealRequests() throws Exception {
        problem(send("GET","/api/v1/admin/filter-probe",userToken,null),403,"ACCESS_DENIED");
        assertEquals(200,send("GET","/api/v1/admin/filter-probe",adminToken,null).statusCode());
        problem(send("GET","/api/v1/me/filter-probe",null,null),401,"UNAUTHENTICATED");
        assertEquals(3,calls(filter,"doFilter"));assertEquals(2,calls(jwt,"extractUsername"));
        assertEquals(2,calls(users,"loadUserByUsername"));assertEquals(1,Collections.frequency(recorder.trace,"controller"));
    }

    @ParameterizedTest
    @ValueSource(ints={503,500})
    void serverFailuresKeepClassificationAndSafeCorrelatedEvents(int status) throws Exception {
        String canary="routing-failure-canary";secrets.add(canary);
        RuntimeException failure=status==503
                ?new org.springframework.dao.DataAccessResourceFailureException(canary,new SQLTransientConnectionException(canary,"08006"))
                :new IllegalStateException(canary);
        doThrow(failure).when(users).loadUserByUsername(anyString());
        problem(send("GET","/api/v1/me/filter-probe",userToken,null),status,status==503?"SERVICE_UNAVAILABLE":"SYSTEM_ERROR");
        assertEquals(1,calls(filter,"doFilter"));assertEquals(1,calls(jwt,"extractUsername"));
        assertEquals(1,calls(users,"loadUserByUsername"));assertEquals(1,lastServerFailureEvents);
        assertEquals(0,lastRejectedEvents);assertFalse(recorder.trace.contains("controller"));
    }

    private void problem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode());
        var body=json.readTree(response.body());
        assertEquals(code,body.path("code").asText());
        assertEquals(status==400?7:6,body.size());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        for(String secret:secrets)assertFalse(response.body().contains(secret),"Sensitive value in error response");
    }

    private HttpResponse<String> send(String method,String path,String token,Object data) throws Exception {
        try(var logs=new TestApplicationLogs()) {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type","application/json").header("X-Request-Id","spoofed-routing-probe")
                    .header("X-Forwarded-For",clientAddress);
            if(token!=null)builder.header("Authorization","Bearer "+token);
            builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(logs.events("http.request.completed").isEmpty() && System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(1,logs.events("http.request.completed").size());
            lastRejectedEvents=logs.events("security.jwt.rejected").size();
            lastServerFailureEvents=logs.events("system.database_unavailable").size()+logs.events("system.unhandled_error").size();
            String id=response.headers().firstValue("X-Request-Id").orElseThrow();
            assertEquals(id,UUID.fromString(id).toString());assertNotEquals("spoofed-routing-probe",id);
            for(var event:logs.events()) {
                assertEquals(id,event.getMDCPropertyMap().get("requestId"));
                assertNull(event.getThrowableProxy());
                String rendered=event.getFormattedMessage()+TestApplicationLogs.fields(event)+event.getMDCPropertyMap();
                for(String secret:secrets)assertFalse(rendered.contains(secret),"Sensitive value in application event");
            }
            return response;
        }
    }
    private static long calls(Object spy,String method) {
        return mockingDetails(spy).getInvocations().stream().filter(i -> i.getMethod().getName().equals(method)).count();
    }
    private static String randomValue() { return Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded()); }
}
