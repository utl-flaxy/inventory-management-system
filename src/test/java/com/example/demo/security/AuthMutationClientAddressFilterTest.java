package com.example.demo.security;

import com.example.demo.exception.AuthMutationSourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthMutationClientAddressFilterTest {
    private final SecurityProblemWriter problems = new SecurityProblemWriter(tools.jackson.databind.json.JsonMapper.builder()
            .addMixIn(org.springframework.http.ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class)
            .build());

    private MockHttpServletRequest request(String peer) {
        var request = new MockHttpServletRequest("POST", "/auth/logout-all");
        request.setRemoteAddr(peer);
        return request;
    }

    @Test
    void untrustedForwardedHeadersCannotRotateSourceEvenAfterFrameworkRewriting() throws Exception {
        var properties = new LoginRateLimitProperties();
        for (String claim : List.of("203.0.113.1", "203.0.113.2")) {
            var request = request("198.51.100.1");
            request.addHeader("X-Forwarded-For", claim);
            request.addHeader("Forwarded", "for=" + claim);
            new LoginClientAddressFilter(properties, problems).doFilter(request, new MockHttpServletResponse(),
                    (captured, output) -> new ForwardedHeaderFilter().doFilter(captured, output,
                            (rewritten, ignored) -> assertEquals("198.51.100.1",
                                    LoginClientAddressFilter.source((jakarta.servlet.http.HttpServletRequest) rewritten))));
        }
    }

    @Test
    void trustedProxySeparatesSourcesWithContextPathAndNormalizesIpv6() throws Exception {
        var properties = new LoginRateLimitProperties();
        properties.setRequireTrustedProxy(true); properties.setTrustedProxies(List.of("127.0.0.1"));
        for (String client : List.of("198.51.100.1", "198.51.100.2", "2001:db8::1")) {
            var request = request("127.0.0.1");
            request.setContextPath("/app"); request.setRequestURI("/app/auth/logout-all");
            request.addHeader("X-Forwarded-For", client);
            var invoked = new boolean[1];
            new LoginClientAddressFilter(properties, problems).doFilter(request, new MockHttpServletResponse(),
                    (input, output) -> {
                        invoked[0] = true;
                        assertEquals(LoginClientAddressFilter.literalAddress(client), LoginClientAddressFilter.source(request));
                    });
            assertTrue(invoked[0]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "chain", "forwarded", "untrusted", "unknown-peer", "hostname"})
    void unverifiableSourcesFailClosedWithoutSharedBucketOrSourceDisclosure(String scenario) throws Exception {
        var properties = new LoginRateLimitProperties();
        properties.setRequireTrustedProxy(true); properties.setTrustedProxies(List.of("127.0.0.1"));
        var request = request(scenario.equals("untrusted") ? "198.51.100.1"
                : scenario.equals("unknown-peer") ? "unknown" : "127.0.0.1");
        if (!scenario.equals("missing")) request.addHeader("X-Forwarded-For", switch (scenario) {
            case "chain" -> "203.0.113.8, 203.0.113.9";
            case "hostname" -> "untrusted.example";
            default -> "203.0.113.8";
        });
        if (scenario.equals("duplicate")) request.addHeader("X-Forwarded-For", "203.0.113.9");
        if (scenario.equals("forwarded")) request.addHeader("Forwarded", "for=203.0.113.8");
        var response = new MockHttpServletResponse();
        new LoginClientAddressFilter(properties, problems).doFilter(request, response,
                (input, output) -> fail("Unverified authentication mutation reached controller"));
        assertEquals(503, response.getStatus());
        var body = new tools.jackson.databind.json.JsonMapper().readTree(response.getContentAsString());
        assertEquals(6, body.size());
        assertEquals("AUTH_MUTATION_SOURCE_UNAVAILABLE", body.path("code").asText());
        assertEquals("/auth/logout-all", body.path("instance").asText());
        assertNull(response.getHeader("Retry-After"));
        assertFalse(response.getContentAsString().contains("203.0.113"));
        assertThrows(AuthMutationSourceUnavailableException.class, () -> LoginClientAddressFilter.source(request));
    }
}
