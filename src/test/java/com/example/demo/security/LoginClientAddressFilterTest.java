package com.example.demo.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoginClientAddressFilterTest {
    private MockHttpServletRequest request(String peer) {
        var request = new MockHttpServletRequest("POST", "/auth/login"); request.setRemoteAddr(peer); return request;
    }

    private String source(MockHttpServletRequest request, List<String> trusted) throws Exception {
        var properties = new LoginRateLimitProperties(); properties.setTrustedProxies(trusted);
        var response = new MockHttpServletResponse();
        var observed = new String[1];
        new LoginClientAddressFilter(properties).doFilter(request, response, (captured, output) ->
                new ForwardedHeaderFilter().doFilter(captured, output, (rewritten, ignored) ->
                        observed[0] = LoginClientAddressFilter.source((jakarta.servlet.http.HttpServletRequest) rewritten)));
        return observed[0];
    }

    @Test
    void unknownTransportPeerCannotRotateForwardedClaimsEvenAfterSpringRewritesRemoteAddr() throws Exception {
        var request = request("198.51.100.1");
        request.addHeader("X-Forwarded-For", "203.0.113.8");
        request.addHeader("Forwarded", "for=203.0.113.9;proto=https");
        assertEquals("198.51.100.1", source(request, List.of("127.0.0.1")));
    }

    @Test
    void trustDefaultsToEmptyIncludingLoopbackAndPrivatePeers() throws Exception {
        var request = request("127.0.0.1"); request.addHeader("X-Forwarded-For", "203.0.113.8");
        assertEquals("127.0.0.1", source(request, List.of()));
    }

    @Test
    void explicitlyTrustedDockerGatewayCanForwardSeparateUsers() throws Exception {
        for (String client : List.of("203.0.113.8", "203.0.113.9", "2001:db8::1")) {
            var request = request("172.18.0.1"); request.addHeader("X-Forwarded-For", client);
            assertEquals(LoginClientAddressFilter.literalAddress(client), source(request, List.of("172.18.0.1")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example", "203.0.113.8, 203.0.113.9", "203.0.113.8:80", "unknown",
            "999.1.1.1", "203.0.113", "::1%eth0", "[::1]", " 203.0.113.8 "})
    void malformedOrChainedProxyClaimsFallBackToTransportPeer(String value) throws Exception {
        var request = request("127.0.0.1"); request.addHeader("X-Forwarded-For", value);
        // Only capture is needed here; Spring's unrelated header parser may itself reject malformed claims.
        var properties = new LoginRateLimitProperties(); properties.setTrustedProxies(List.of("127.0.0.1"));
        new LoginClientAddressFilter(properties).doFilter(request, new MockHttpServletResponse(), (input, output) ->
                assertEquals("127.0.0.1", LoginClientAddressFilter.source((jakarta.servlet.http.HttpServletRequest) input)));
    }

    @Test
    void duplicateXffOrUnexpectedForwardedHeaderFailsClosedAtTrustedPeer() throws Exception {
        var duplicate = request("127.0.0.1");
        duplicate.addHeader("X-Forwarded-For", "203.0.113.8"); duplicate.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("127.0.0.1", source(duplicate, List.of("127.0.0.1")));
        var forwarded = request("127.0.0.1");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.8"); forwarded.addHeader("Forwarded", "for=203.0.113.9");
        assertEquals("127.0.0.1", source(forwarded, List.of("127.0.0.1")));
    }

    @Test
    void ipv6AliasesAndIpv4MappedIpv6HaveOneIdentity() {
        assertEquals(LoginClientAddressFilter.literalAddress("2001:db8::1"),
                LoginClientAddressFilter.literalAddress("2001:0db8:0:0:0:0:0:1"));
        assertEquals("127.0.0.1", LoginClientAddressFilter.literalAddress("::ffff:127.0.0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "172.18.0.0/16", "", "*"})
    void invalidTrustedProxyConfigurationIsRejected(String value) {
        var properties = new LoginRateLimitProperties(); properties.setTrustedProxies(List.of(value));
        assertThrows(IllegalArgumentException.class, () -> new LoginClientAddressFilter(properties));
    }

    @Test
    void missingCaptureUsesSingleUnknownBucketRatherThanTrustingRewrittenAddress() {
        assertEquals("unknown", LoginClientAddressFilter.source(request("203.0.113.8")));
    }
}
