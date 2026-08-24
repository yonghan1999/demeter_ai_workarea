package com.demeter.backend.maintenance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.maintenance")
public record MaintenanceProperties(
        boolean enabled,
        @NotNull Duration sessionRetention,
        @NotNull Duration commandReplayRetention,
        @NotNull Duration maintenanceRunRetention,
        @NotNull Duration runStaleAfter,
        @NotNull Duration ocrRetryCommandRetention,
        @NotNull Duration ocrDocumentRetention,
        @NotNull Duration ocrStorageOrphanGracePeriod,
        @Min(1) @Max(1000) int batchSize,
        @Min(1) @Max(100) int maxBatchesPerRun,
        @Min(1) @Max(10000) int ocrStorageOrphanScanLimit) {

    public MaintenanceProperties {
        requirePositive(sessionRetention, "session retention");
        requirePositive(commandReplayRetention, "command replay retention");
        requirePositive(maintenanceRunRetention, "maintenance run retention");
        requirePositive(runStaleAfter, "stale run threshold");
        requirePositive(ocrRetryCommandRetention, "OCR retry command retention");
        requirePositive(ocrDocumentRetention, "OCR document retention");
        requirePositive(ocrStorageOrphanGracePeriod, "OCR storage orphan grace period");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("Maintenance " + name + " must be positive");
        }
    }
}
