package com.demeter.backend.audit.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.audit")
public record AuditProperties(
        @Min(1024) @Max(1_048_576) int maxDetailsBytes) {
}
