package com.example.demo.security;

import com.example.demo.exception.LoginSourceUnavailableException;
import com.example.demo.exception.RegistrationSourceUnavailableException;
import com.example.demo.exception.AuthMutationSourceUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/** Captures the login/registration transport peer before Spring's ForwardedHeaderFilter can rewrite it. */
public class LoginClientAddressFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = LoginClientAddressFilter.class.getName() + ".source";
    private final Set<String> trustedPeers = new HashSet<>();
    private final boolean requireTrustedProxy;
    private final SecurityProblemWriter problems;

    public LoginClientAddressFilter(LoginRateLimitProperties properties, SecurityProblemWriter problems) {
        properties.validate();
        this.requireTrustedProxy = properties.isRequireTrustedProxy();
        this.problems = problems;
        for (String peer : properties.getTrustedProxies()) {
            String parsed = literalAddress(peer);
            if (parsed == null) throw new IllegalArgumentException("Trusted login proxies must be literal IP addresses");
            trustedPeers.add(parsed);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || (!(request.getContextPath() + "/auth/login").equals(request.getRequestURI())
                && !isRegistration(request) && !isAuthMutation(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String peer = literalAddress(request.getRemoteAddr());
        String source = requireTrustedProxy ? null : peer;
        // Caddy replaces XFF with one socket client IP and removes Forwarded. Reject chains/duplicate claims.
        if (trustedPeers.contains(peer) && request.getHeader("Forwarded") == null) {
            var headers = request.getHeaders("X-Forwarded-For");
            String forwarded = headers.hasMoreElements() ? literalAddress(headers.nextElement()) : null;
            source = forwarded != null && !headers.hasMoreElements() ? forwarded : null;
        } else if (trustedPeers.contains(peer)) {
            source = null;
        }
        if (source == null) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    isAuthMutation(request) ? "AUTH_MUTATION_SOURCE_UNAVAILABLE" :
                            isRegistration(request) ? "REGISTRATION_SOURCE_UNAVAILABLE" : "LOGIN_SOURCE_UNAVAILABLE",
                    isAuthMutation(request) ? AuthMutationSourceUnavailableException.DETAIL :
                            isRegistration(request) ? RegistrationSourceUnavailableException.DETAIL : LoginSourceUnavailableException.DETAIL);
            return;
        }
        request.setAttribute(ATTRIBUTE, source);
        chain.doFilter(request, response);
    }

    public static String source(HttpServletRequest request) {
        Object captured = request.getAttribute(ATTRIBUTE);
        // A missing filter cannot turn a rewritten remoteAddr into a trusted client identity.
        if (captured instanceof String address && literalAddress(address) != null) return address;
        if (isAuthMutation(request)) throw new AuthMutationSourceUnavailableException();
        if (isRegistration(request)) throw new RegistrationSourceUnavailableException();
        throw new LoginSourceUnavailableException();
    }

    private static boolean isRegistration(HttpServletRequest request) {
        return (request.getContextPath() + "/auth/register").equals(request.getRequestURI());
    }

    private static boolean isAuthMutation(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && ((request.getContextPath() + "/auth/logout-all").equals(request.getRequestURI())
                || (request.getContextPath() + "/auth/password").equals(request.getRequestURI())
                || isAdminRevocation(request));
    }

    private static boolean isAdminRevocation(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/api/v1/admin/users/";
        String path = request.getRequestURI();
        if (!path.startsWith(prefix)) return false;
        // One segment, including malformed UID input, so UID validation cannot bypass source capture.
        // The container bounds the request line; only verified literal IPs allocate limiter state.
        return path.substring(prefix.length()).matches("[^/;]+/token-revocations");
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
