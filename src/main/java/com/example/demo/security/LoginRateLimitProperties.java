package com.example.demo.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConfigurationProperties("app.login.rate-limit")
@Getter
@Setter
public class LoginRateLimitProperties {
    private int pairLimit = 5;
    private int sourceLimit = 100;
    // Bind legacy values to fail explicitly instead of silently ignoring an old deployment override.
    private Integer globalLimit;
    private int maxConcurrent = 4;
    private Duration window = Duration.ofMinutes(1);
    private int maxEntries = 4096;
    // Exact transport peers only; never implicitly trust private networks or forwarded headers.
    private List<String> trustedProxies = List.of();
    private boolean requireTrustedProxy;

    public void validate() {
        if (globalLimit != null)
            throw new IllegalArgumentException("Login global-limit was replaced by max-concurrent");
        if (pairLimit < 1 || sourceLimit < 1 || maxConcurrent < 1 || maxConcurrent > 64
                || maxEntries < 2 || maxEntries > 100_000
                || window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid login rate limit configuration");
        }
        if (trustedProxies == null || (requireTrustedProxy && trustedProxies.isEmpty()))
            throw new IllegalArgumentException("Verified trusted login proxies are required");
        for (String peer : trustedProxies)
            if (LoginClientAddressFilter.literalAddress(peer) == null)
                throw new IllegalArgumentException("Trusted login proxies must be literal IP addresses");
    }

    public void validateProduction() {
        if (!requireTrustedProxy)
            throw new IllegalArgumentException("Production login requires a trusted proxy");
        validate();
    }
}
