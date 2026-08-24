package com.demeter.backend.common.idempotency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.idempotency")
public record IdempotencyProperties(
        @Min(1024) @Max(4_194_304) int maxReplayResponseBytes) {

    public IdempotencyProperties {
        if (maxReplayResponseBytes < 1) {
            throw new IllegalArgumentException("Maximum idempotency replay response size must be positive");
        }
    }
}
