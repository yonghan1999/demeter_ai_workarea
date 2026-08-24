package com.demeter.backend.migration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.database")
public record DatabaseSchemaProperties(
        @Min(1) @Max(10_000) int minimumSchemaVersion) {
}
