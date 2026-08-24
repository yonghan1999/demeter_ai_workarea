package com.demeter.backend.auth.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.auth")
public record AuthProperties(
        @NotNull Duration sessionTtl,
        @Min(1) @Max(100) int maxActiveSessions) {

    public AuthProperties {
        if (sessionTtl != null && (sessionTtl.isZero() || sessionTtl.isNegative())) {
            throw new IllegalArgumentException("Authentication session TTL must be positive");
        }
    }
}
