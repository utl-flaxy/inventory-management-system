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
    private int globalLimit = 1000;
    private Duration window = Duration.ofMinutes(1);
    private int maxEntries = 4096;
    // Exact transport peers only; never implicitly trust private networks or forwarded headers.
    private List<String> trustedProxies = List.of();

    void validate() {
        if (pairLimit < 1 || sourceLimit < 1 || globalLimit < 1 || maxEntries < 2 || maxEntries > 100_000
                || window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid login rate limit configuration");
        }
    }
}
