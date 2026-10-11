package com.example.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/** Captures the transport peer before Spring's ForwardedHeaderFilter can rewrite it. */
public class LoginClientAddressFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = LoginClientAddressFilter.class.getName() + ".source";
    private final Set<String> trustedPeers = new HashSet<>();

    public LoginClientAddressFilter(LoginRateLimitProperties properties) {
        for (String peer : properties.getTrustedProxies()) {
            String parsed = literalAddress(peer);
            if (parsed == null) throw new IllegalArgumentException("Trusted login proxies must be literal IP addresses");
            trustedPeers.add(parsed);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/auth/login").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String peer = literalAddress(request.getRemoteAddr());
        String source = peer == null ? "unknown" : peer;
        // Caddy replaces XFF with one socket client IP and removes Forwarded. Reject chains/duplicate claims.
        if (trustedPeers.contains(peer) && request.getHeader("Forwarded") == null) {
            var headers = request.getHeaders("X-Forwarded-For");
            String forwarded = headers.hasMoreElements() ? literalAddress(headers.nextElement()) : null;
            if (forwarded != null && !headers.hasMoreElements()) source = forwarded;
        }
        request.setAttribute(ATTRIBUTE, source);
        chain.doFilter(request, response);
    }

    public static String source(HttpServletRequest request) {
        Object captured = request.getAttribute(ATTRIBUTE);
        // A missing filter cannot turn a rewritten remoteAddr into a trusted client identity.
        return captured instanceof String address ? address : "unknown";
    }

    static String literalAddress(String value) {
        if (value == null || value.length() > 45 || value.isEmpty()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null; // No hostnames, zones, ports, or DNS lookup.
            try { return InetAddress.getByName(value).getHostAddress(); }
            catch (UnknownHostException ex) { return null; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}")) return null;
            int octet = Integer.parseInt(part);
            if (octet > 255) return null;
            if (!canonical.isEmpty()) canonical.append('.');
            canonical.append(octet);
        }
        return canonical.toString();
    }
}
