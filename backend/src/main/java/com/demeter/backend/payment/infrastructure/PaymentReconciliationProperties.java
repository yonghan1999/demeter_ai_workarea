package com.demeter.backend.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.payment.reconciliation")
public record PaymentReconciliationProperties(
        boolean enabled,
        @Min(1) @Max(1000) int sampleSize) {
}
