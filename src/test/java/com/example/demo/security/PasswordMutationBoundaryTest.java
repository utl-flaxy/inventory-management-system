package com.example.demo.security;

import com.example.demo.exception.AuthMutationRateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PasswordMutationBoundaryTest {
    @Test void forceRevocationSharesConcurrencyAndHasItsOwnWindow() {
        var p = new AuthMutationRateLimitProperties();p.setSourceLimit(1);p.setMaxEntries(2);
        var limiter = new AuthMutationRateLimiter(p, Clock.systemUTC());
        try (var first = limiter.acquire(AuthMutationRateLimiter.Operation.FORCE_REVOKE, "198.51.100.1");
             var second = limiter.acquire(AuthMutationRateLimiter.Operation.LOGOUT_ALL, "198.51.100.1")) {
            assertThrows(AuthMutationRateLimitExceededException.class, () -> limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.2"));
        }
        assertThrows(AuthMutationRateLimitExceededException.class, () -> limiter.acquire(AuthMutationRateLimiter.Operation.FORCE_REVOKE, "198.51.100.1"));
        try (var next = limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.1")) { assertEquals(2, limiter.entryCount()); }
        assertEquals(0, limiter.inFlightCount());
    }

    @Test void onlyProtectedAdminRevocationPostsCaptureSourceIncludingMalformedUidAndContextPath() throws Exception {
        var p = new LoginRateLimitProperties();p.setRequireTrustedProxy(true);p.setTrustedProxies(List.of("127.0.0.1"));
        var filter = new LoginClientAddressFilter(p, problems());
        for (String segment : List.of(java.util.UUID.randomUUID().toString(), "malformed", "x".repeat(129))) {
            var request = new MockHttpServletRequest("POST", "/app/api/v1/admin/users/" + segment + "/token-revocations");
            request.setContextPath("/app");request.setRemoteAddr("127.0.0.1");request.addHeader("X-Forwarded-For", "198.51.100.1");
            filter.doFilter(request, new MockHttpServletResponse(), (in, out) -> assertEquals("198.51.100.1", LoginClientAddressFilter.source(request)));
            var direct = new MockHttpServletRequest("POST", request.getRequestURI());direct.setContextPath("/app");direct.setRemoteAddr("198.51.100.2");direct.addHeader("X-Forwarded-For", "198.51.100.1");
            var response = new MockHttpServletResponse();filter.doFilter(direct, response, (in, out) -> fail("Untrusted peer admitted"));
            assertEquals(503, response.getStatus());assertTrue(response.getContentAsString().contains("AUTH_MUTATION_SOURCE_UNAVAILABLE"));
        }
        for (String path : List.of("/api/v1/admin/users/a/token-revocations/", "/api/v1/admin/users/a/other", "/api/v1/admin/users/a/b/token-revocations", "/api/v1/admin/orders"))
            assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST", path)));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/admin/users/a/token-revocations")));
    }
    @Test void separateOperationWindowsShareConcurrencyAndOneStateBound() {
        var p = new AuthMutationRateLimitProperties();p.setSourceLimit(1);p.setMaxEntries(2);
        var limiter = new AuthMutationRateLimiter(p, Clock.systemUTC());
        try (var logout = limiter.acquire(AuthMutationRateLimiter.Operation.LOGOUT_ALL, "198.51.100.1");
             var password = limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.1")) {
            assertEquals(2, limiter.entryCount());assertEquals(2, limiter.inFlightCount());
            assertThrows(AuthMutationRateLimitExceededException.class, () -> limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.2"));
        }
        assertEquals(0, limiter.inFlightCount());
        assertThrows(AuthMutationRateLimitExceededException.class, () -> limiter.acquire(AuthMutationRateLimiter.Operation.LOGOUT_ALL, "198.51.100.1"));
        assertThrows(AuthMutationRateLimitExceededException.class, () -> limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.1"));
        try (var next = limiter.acquire(AuthMutationRateLimiter.Operation.PASSWORD_CHANGE, "198.51.100.2")) { assertEquals(2, limiter.entryCount()); }
    }

    private SecurityProblemWriter problems() {
        return new SecurityProblemWriter(tools.jackson.databind.json.JsonMapper.builder()
                .addMixIn(org.springframework.http.ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class).build());
    }
    @Test void onlyTheExactProtectedPasswordPostCapturesTheVerifiedProxySource() throws Exception {
        var p = new LoginRateLimitProperties();p.setRequireTrustedProxy(true);p.setTrustedProxies(List.of("127.0.0.1"));
        var filter = new LoginClientAddressFilter(p, problems());
        var request = new MockHttpServletRequest("POST", "/app/auth/password");request.setContextPath("/app");request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (input, output) -> assertEquals("198.51.100.2", LoginClientAddressFilter.source(request)));
        for (String path : List.of("/auth/password-extra", "/auth/password/", "/auth/other")) {
            var unrelated = new MockHttpServletRequest("POST", path);unrelated.setRemoteAddr("127.0.0.1");
            assertTrue(filter.shouldNotFilter(unrelated));
        }
    }
    @Test void untrustedProxyCannotSpoofThePasswordSourceAndRequiredProxyFailsClosed() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/password");request.setRemoteAddr("198.51.100.1");request.addHeader("X-Forwarded-For", "203.0.113.1");
        new LoginClientAddressFilter(new LoginRateLimitProperties(), problems()).doFilter(request, new MockHttpServletResponse(),
                (input, output) -> assertEquals("198.51.100.1", LoginClientAddressFilter.source(request)));
        var p = new LoginRateLimitProperties();p.setRequireTrustedProxy(true);p.setTrustedProxies(List.of("127.0.0.1"));
        var response = new MockHttpServletResponse();
        new LoginClientAddressFilter(p, problems()).doFilter(request, response, (input, output) -> fail("Untrusted proxy admitted"));
        assertEquals(503, response.getStatus());assertTrue(response.getContentAsString().contains("AUTH_MUTATION_SOURCE_UNAVAILABLE"));
    }
}
