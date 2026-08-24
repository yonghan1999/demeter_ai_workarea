package com.demeter.backend.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(100) @Max(1_000_000) int maximumKeys,
        @NotNull Duration expireAfterAccess,
        @Valid @NotNull Policy login,
        @Valid @NotNull Policy authenticated,
        @Valid @NotNull Policy ocrUpload,
        @Valid @NotNull Policy ocrRetry) {

    public record Policy(
            @Min(1) @Max(1_000_000) long capacity,
            @Min(1) @Max(1_000_000) long refillTokens,
            @NotNull Duration refillPeriod) {

        public Policy {
            if (refillPeriod != null && (refillPeriod.isZero() || refillPeriod.isNegative())) {
                throw new IllegalArgumentException("Rate-limit refill period must be positive");
            }
        }
    }

    public RateLimitProperties {
        if (expireAfterAccess != null && (expireAfterAccess.isZero() || expireAfterAccess.isNegative())) {
            throw new IllegalArgumentException("Rate-limit cache expiry must be positive");
        }
    }
}
