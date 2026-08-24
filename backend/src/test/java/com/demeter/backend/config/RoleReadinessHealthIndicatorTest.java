package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.maintenance.MaintenanceProperties;
import com.demeter.backend.maintenance.domain.MaintenanceRun;
import com.demeter.backend.maintenance.domain.MaintenanceRun.MaintenanceResultSnapshot;
import com.demeter.backend.maintenance.infrastructure.MaintenanceRunRepository;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class RoleReadinessHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);
    private static final HandwrittenBillOcrProvider PROVIDER = request -> null;

    @Test
    void apiRoleDoesNotDependOnOcrStorageOrProviderAvailability() {
        var indicator = indicator(
                RuntimeRole.API,
                failingStorage(),
                List.of(),
                maintenance(false),
                Optional.empty());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("role", "API");
    }

    @Test
    void workerRoleRequiresExactlyOneProviderAndAvailableStorage() {
        assertThat(indicator(
                        RuntimeRole.WORKER,
                        availableStorage(),
                        List.of(),
                        maintenance(false),
                        Optional.empty())
                .health()
                .getStatus()).isEqualTo(Status.DOWN);

        assertThat(indicator(
                        RuntimeRole.WORKER,
                        failingStorage(),
                        List.of(PROVIDER),
                        maintenance(false),
                        Optional.empty())
                .health()
                .getStatus()).isEqualTo(Status.DOWN);

        assertThat(indicator(
                        RuntimeRole.WORKER,
                        availableStorage(),
                        List.of(PROVIDER),
                        maintenance(false),
                        Optional.empty())
                .health()
                .getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void maintenanceRoleTracksTheLatestMaintenanceRunStatus() {
        assertThat(indicator(
                        RuntimeRole.MAINTENANCE,
                        availableStorage(),
                        List.of(),
                        maintenance(false),
                        Optional.of(completedRun(false)))
                .health()
                .getStatus()).isEqualTo(Status.UP);

        assertThat(indicator(
                        RuntimeRole.MAINTENANCE,
                        availableStorage(),
                        List.of(),
                        maintenance(false),
                        Optional.of(completedRun(true)))
                .health()
                .getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        assertThat(indicator(
                        RuntimeRole.MAINTENANCE,
                        availableStorage(),
                        List.of(),
                        maintenance(false),
                        Optional.of(failedRun()))
                .health()
                .getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void maintenanceRoleReportsDownForAStaleRunningJobOrUnavailableRequiredStorage() {
        assertThat(indicator(
                        RuntimeRole.MAINTENANCE,
                        availableStorage(),
                        List.of(),
                        maintenance(false),
                        Optional.of(runningRun(CLOCK.instant().minus(Duration.ofHours(3)))))
                .health()
                .getStatus()).isEqualTo(Status.DOWN);

        assertThat(indicator(
                        RuntimeRole.MAINTENANCE,
                        failingStorage(),
                        List.of(),
                        maintenance(true),
                        Optional.empty())
                .health()
                .getStatus()).isEqualTo(Status.DOWN);
    }

    private static RoleReadinessHealthIndicator indicator(
            RuntimeRole role,
            OcrDocumentStorage storage,
            List<HandwrittenBillOcrProvider> providers,
            MaintenanceProperties maintenance,
            Optional<MaintenanceRun> latestRun) {
        return new RoleReadinessHealthIndicator(
                new RuntimeRoleProperties(role),
                storage,
                providers,
                maintenance,
                repository(latestRun),
                CLOCK);
    }

    private static MaintenanceProperties maintenance(boolean enabled) {
        return new MaintenanceProperties(
                enabled,
                Duration.ofDays(7),
                Duration.ofDays(90),
                Duration.ofDays(180),
                Duration.ofHours(2),
                Duration.ofDays(90),
                Duration.ofDays(30),
                Duration.ofHours(1),
                100,
                10,
                100);
    }

    private static MaintenanceRun completedRun(boolean partialFailure) {
        MaintenanceRun run = new MaintenanceRun("system.maintenance", CLOCK.instant().minus(Duration.ofMinutes(5)));
        run.complete(new MaintenanceResultSnapshot(0, 0, 0, 0, 0, partialFailure ? 1 : 0, partialFailure, null),
                CLOCK.instant());
        return run;
    }

    private static MaintenanceRun failedRun() {
        MaintenanceRun run = new MaintenanceRun("system.maintenance", CLOCK.instant().minus(Duration.ofMinutes(5)));
        run.fail(CLOCK.instant(), "maintenance failed");
        return run;
    }

    private static MaintenanceRun runningRun(Instant startedAt) {
        return new MaintenanceRun("system.maintenance", startedAt);
    }

    private static MaintenanceRunRepository repository(Optional<MaintenanceRun> latestRun) {
        return (MaintenanceRunRepository) Proxy.newProxyInstance(
                MaintenanceRunRepository.class.getClassLoader(),
                new Class<?>[] {MaintenanceRunRepository.class},
                (proxy, method, args) -> {
                    if ("findTopByRunTypeOrderByStartedAtDescIdDesc".equals(method.getName())) {
                        return latestRun;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static OcrDocumentStorage availableStorage() {
        return new TestStorage(false);
    }

    private static OcrDocumentStorage failingStorage() {
        return new TestStorage(true);
    }

    private record TestStorage(boolean failAvailability) implements OcrDocumentStorage {
        @Override
        public String store(long tenantId, String objectName, OcrDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OcrDocument load(String storageKey, String originalFilename, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verifyAvailability() {
            if (failAvailability) {
                throw new IllegalStateException("storage unavailable");
            }
        }
    }
}
