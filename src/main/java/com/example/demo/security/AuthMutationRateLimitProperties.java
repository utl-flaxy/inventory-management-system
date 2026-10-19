package com.example.demo.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties("app.auth.mutation.rate-limit")
@Getter
@Setter
public class AuthMutationRateLimitProperties {
    private int sourceLimit = 5;
    private int maxConcurrent = 2;
    private Duration window = Duration.ofMinutes(1);
    private int maxEntries = 4096;

    public void validate() {
        if (sourceLimit < 1 || maxConcurrent < 1 || maxConcurrent > 64
                || maxEntries < 1 || maxEntries > 100_000
                || window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid authentication mutation rate limit configuration");
        }
    }
}
