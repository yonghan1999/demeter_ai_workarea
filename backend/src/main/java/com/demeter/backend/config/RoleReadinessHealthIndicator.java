package com.demeter.backend.config;

import com.demeter.backend.maintenance.MaintenanceProperties;
import com.demeter.backend.maintenance.domain.MaintenanceRun;
import com.demeter.backend.maintenance.domain.MaintenanceRunStatus;
import com.demeter.backend.maintenance.infrastructure.MaintenanceRunRepository;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Keeps readiness tied to the dependencies required by the selected runtime role.
 * The API role deliberately does not depend on OCR storage availability.
 */
@Component("roleReadiness")
public class RoleReadinessHealthIndicator implements HealthIndicator {

    private final RuntimeRoleProperties runtime;
    private final OcrDocumentStorage storage;
    private final List<HandwrittenBillOcrProvider> providers;
    private final MaintenanceProperties maintenance;
    private final MaintenanceRunRepository runRepository;
    private final Clock clock;

    public RoleReadinessHealthIndicator(
            RuntimeRoleProperties runtime,
            OcrDocumentStorage storage,
            List<HandwrittenBillOcrProvider> providers,
            MaintenanceProperties maintenance,
            MaintenanceRunRepository runRepository,
            Clock clock) {
        this.runtime = runtime;
        this.storage = storage;
        this.providers = List.copyOf(providers);
        this.maintenance = maintenance;
        this.runRepository = runRepository;
        this.clock = clock;
    }

    @Override
    public Health health() {
        return switch (runtime.role()) {
            case ALL, API -> Health.up().withDetail("role", runtime.role().name()).build();
            case WORKER -> workerHealth();
            case MAINTENANCE -> maintenanceHealth();
        };
    }

    private Health workerHealth() {
        if (providers.size() != 1) {
            return Health.down()
                    .withDetail("role", "WORKER")
                    .withDetail("providerCount", providers.size())
                    .build();
        }
        try {
            storage.verifyAvailability();
            return Health.up().withDetail("role", "WORKER").build();
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("role", "WORKER").build();
        }
    }

    private Health maintenanceHealth() {
        try {
            if (maintenance.enabled()) {
                storage.verifyAvailability();
            }
            MaintenanceRun latest = runRepository
                    .findTopByRunTypeOrderByStartedAtDescIdDesc("system.maintenance")
                    .orElse(null);
            if (latest == null) {
                return Health.up().withDetail("role", "MAINTENANCE").build();
            }
            if (latest.getStatus() == MaintenanceRunStatus.FAILED) {
                return Health.down().withDetail("role", "MAINTENANCE").build();
            }
            if (latest.getStatus() == MaintenanceRunStatus.PARTIAL_FAILURE) {
                return Health.outOfService().withDetail("role", "MAINTENANCE").build();
            }
            if (latest.getStatus() == MaintenanceRunStatus.RUNNING
                    && latest.getStartedAt().isBefore(Instant.now(clock).minus(maintenance.runStaleAfter()))) {
                return Health.down().withDetail("role", "MAINTENANCE").build();
            }
            return Health.up().withDetail("role", "MAINTENANCE").build();
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("role", "MAINTENANCE").build();
        }
    }
}
