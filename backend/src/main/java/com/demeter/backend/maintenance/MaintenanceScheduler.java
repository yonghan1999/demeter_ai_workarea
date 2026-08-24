package com.demeter.backend.maintenance;

import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnRuntimeRole({RuntimeRole.MAINTENANCE})
@ConditionalOnProperty(prefix = "demeter.maintenance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final MaintenanceService maintenanceService;

    public MaintenanceScheduler(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @Scheduled(
            fixedDelayString = "${demeter.maintenance.fixed-delay:6h}",
            initialDelayString = "${demeter.maintenance.initial-delay:1m}")
    @SchedulerLock(name = "systemMaintenance", lockAtMostFor = "30m", lockAtLeastFor = "1s")
    public void run() {
        MaintenanceService.MaintenanceResult result = maintenanceService.run();
        if (result.partialFailure()) {
            log.error(
                    "Maintenance completed with partial failures: failureCount={}, summary={}",
                    result.failureCount(),
                    result.failureSummary());
        } else {
            log.info(
                    "Maintenance completed: sessions={}, replays={}, maintenanceRuns={}, ocrRetryCommands={}, "
                            + "ocrDocuments={}, ocrOrphans={}",
                    result.deletedSessions(),
                    result.deletedCommandReplays(),
                    result.deletedMaintenanceRuns(),
                    result.deletedOcrRetryCommands(),
                    result.deletedOcrDocuments(),
                    result.deletedOcrOrphans());
        }
    }
}
