package com.demeter.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.production")
public record ProductionProperties(
        @Min(1) @Max(1000) int replicaCount,
        boolean gatewayRateLimitEnabled) {
}
