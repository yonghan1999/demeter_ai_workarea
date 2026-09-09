package com.demeter.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demeter.admin")
public record AdminProperties(boolean enabled, String accessToken, Duration sessionTtl) {

    public AdminProperties {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("Admin session TTL must be positive");
        }
        if (enabled && (accessToken == null || accessToken.isBlank() || accessToken.length() < 32)) {
            throw new IllegalArgumentException("Admin access token must contain at least 32 characters when enabled");
        }
    }
}
