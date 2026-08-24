package com.demeter.backend.ocr.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.ocr.worker")
public record OcrWorkerProperties(
        boolean enabled,
        @NotNull Duration leaseDuration,
        @NotNull Duration retryDelay,
        @NotNull Duration maxRetryDelay,
        @Min(1) @Max(20) int maxAttempts,
        @Min(1) @Max(1000) int maxTasksPerPoll) {

    public OcrWorkerProperties {
        if (leaseDuration != null && (leaseDuration.isZero() || leaseDuration.isNegative())) {
            throw new IllegalArgumentException("OCR lease duration must be positive");
        }
        if (retryDelay != null && (retryDelay.isZero() || retryDelay.isNegative())) {
            throw new IllegalArgumentException("OCR retry delay must be positive");
        }
        if (maxRetryDelay != null && (maxRetryDelay.isZero() || maxRetryDelay.isNegative())) {
            throw new IllegalArgumentException("OCR maximum retry delay must be positive");
        }
        if (retryDelay != null && maxRetryDelay != null && maxRetryDelay.compareTo(retryDelay) < 0) {
            throw new IllegalArgumentException("OCR maximum retry delay must not be shorter than the base delay");
        }
    }

    public Duration retryDelayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("OCR attempt must be positive");
        }
        Duration delay = retryDelay;
        for (int currentAttempt = 1;
                currentAttempt < attempt && delay.compareTo(maxRetryDelay) < 0;
                currentAttempt++) {
            if (delay.compareTo(maxRetryDelay.dividedBy(2)) > 0) {
                return maxRetryDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
