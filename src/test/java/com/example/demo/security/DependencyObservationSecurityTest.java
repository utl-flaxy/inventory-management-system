package com.example.demo.security;

import io.micrometer.core.instrument.binder.http.HttpJakartaServletRequestTags;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyObservationSecurityTest {
    @Test
    void arbitraryMethodsCannotCreateUnboundedMicrometerTagValues() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            var request = new MockHttpServletRequest("CUSTOM_" + i, "/products");
            values.add(HttpJakartaServletRequestTags.method(request).getValue());
        }
        assertEquals(Set.of("UNKNOWN"), values);
        assertEquals("GET", HttpJakartaServletRequestTags.method(
                new MockHttpServletRequest("GET", "/products")).getValue());
    }

    @Test
    void springMvcObservationAlsoBoundsUnknownMethodTags() {
        var convention = new DefaultServerRequestObservationConvention();
        var context = new ServerRequestObservationContext(
                new MockHttpServletRequest("CUSTOM_METHOD", "/products"), new MockHttpServletResponse());
        Set<String> values = new HashSet<>();
        for (var value : convention.getLowCardinalityKeyValues(context)) {
            if (value.getKey().equals("method")) {
                values.add(value.getValue());
            }
        }
        assertEquals(Set.of("UNKNOWN"), values);
    }

    @Test
    void defaultFirewallRejectsUnknownMethodsBeforeApplicationHandlers() {
        var firewall = new StrictHttpFirewall();
        assertThrows(RequestRejectedException.class, () -> firewall.getFirewalledRequest(
                new MockHttpServletRequest("CUSTOM_METHOD", "/products")));
        assertDoesNotThrow(() -> firewall.getFirewalledRequest(
                new MockHttpServletRequest("GET", "/products")));
    }
}
