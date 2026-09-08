package com.demeter.backend.maintenance;

import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import com.demeter.backend.common.idempotency.infrastructure.BusinessCommandReplayRepository;
import com.demeter.backend.admin.infrastructure.AdminCommandReplayRepository;
import com.demeter.backend.admin.infrastructure.AdminSessionRepository;
import com.demeter.backend.maintenance.domain.MaintenanceRun;
import com.demeter.backend.maintenance.infrastructure.MaintenanceRunRepository;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.ocr.infrastructure.OcrRetryCommandRepository;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.ocr.spi.OcrStorageObject;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.MAINTENANCE)
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final List<OcrTaskStatus> TERMINAL_OCR_STATUSES = List.of(
            OcrTaskStatus.SUCCEEDED,
            OcrTaskStatus.FAILED);

    private final AuthSessionRepository sessionRepository;
    private final BusinessCommandReplayRepository replayRepository;
    private final AdminCommandReplayRepository adminReplayRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final OcrTaskRepository taskRepository;
    private final OcrRetryCommandRepository retryCommandRepository;
    private final OcrDocumentStorage storage;
    private final MaintenanceRunRepository runRepository;
    private final MaintenanceProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final BusinessChainExecutor chainExecutor;
    private final Clock clock;
    private final Counter storageDeleteFailures;
    private final Counter orphanScanFailures;
    private final Counter orphanDeleteCounter;
    private final Counter completedCounter;
    private final Counter partialFailureCounter;
    private final Counter failedCounter;
    private final Timer runTimer;
    private final BusinessChain<MaintenanceContext, MaintenanceResult> maintenanceChain;

    public MaintenanceService(
            AuthSessionRepository sessionRepository,
            BusinessCommandReplayRepository replayRepository,
            AdminCommandReplayRepository adminReplayRepository,
            AdminSessionRepository adminSessionRepository,
            OcrTaskRepository taskRepository,
            OcrRetryCommandRepository retryCommandRepository,
            OcrDocumentStorage storage,
            MaintenanceRunRepository runRepository,
            MaintenanceProperties properties,
            PlatformTransactionManager transactionManager,
            BusinessChainExecutor chainExecutor,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.replayRepository = replayRepository;
        this.adminReplayRepository = adminReplayRepository;
        this.adminSessionRepository = adminSessionRepository;
        this.taskRepository = taskRepository;
        this.retryCommandRepository = retryCommandRepository;
        this.storage = storage;
        this.runRepository = runRepository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.chainExecutor = chainExecutor;
        this.storageDeleteFailures = Counter.builder("demeter.maintenance.ocr_storage_delete.failures")
                .description("OCR documents that maintenance could not delete")
                .register(meterRegistry);
        this.orphanScanFailures = Counter.builder("demeter.maintenance.ocr_storage_orphan_scan.failures")
                .description("OCR storage orphan scans that failed")
                .register(meterRegistry);
        this.orphanDeleteCounter = Counter.builder("demeter.maintenance.ocr_storage_orphans.deleted")
                .description("OCR storage objects deleted because no database task referenced them")
                .register(meterRegistry);
        this.completedCounter = Counter.builder("demeter.maintenance.runs")
                .description("Maintenance run outcomes")
                .tag("outcome", "succeeded")
                .register(meterRegistry);
        this.partialFailureCounter = Counter.builder("demeter.maintenance.runs")
                .description("Maintenance run outcomes")
                .tag("outcome", "partial_failure")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("demeter.maintenance.runs")
                .description("Maintenance run outcomes")
                .tag("outcome", "failed")
                .register(meterRegistry);
        this.runTimer = Timer.builder("demeter.maintenance.duration")
                .description("Maintenance run duration")
                .register(meterRegistry);
        this.clock = clock;
        this.maintenanceChain = buildMaintenanceChain();
    }

    public MaintenanceResult run() {
        MaintenanceContext context = new MaintenanceContext(clock.instant());
        Timer.Sample timer = Timer.start();
        try {
            MaintenanceResult result = chainExecutor.execute(maintenanceChain, context);
            timer.stop(runTimer);
            if (result.partialFailure()) {
                partialFailureCounter.increment();
            } else {
                completedCounter.increment();
            }
            return result;
        } catch (RuntimeException exception) {
            timer.stop(runTimer);
            failedCounter.increment();
            markRunFailed(context, exception);
            throw exception;
        }
    }

    private BusinessChain<MaintenanceContext, MaintenanceResult> buildMaintenanceChain() {
        return BusinessChain.of(
                "system.maintenance",
                BusinessChainExecutionMode.SCHEDULED,
                List.of(
                        BusinessHandler.named("start-run", context -> context.run = runRepository.saveAndFlush(
                                new MaintenanceRun("system.maintenance", context.startedAt))),
                        BusinessHandler.named("cleanup-auth-sessions", this::cleanupSessions),
                        BusinessHandler.named("cleanup-command-replays", this::cleanupCommandReplays),
                        BusinessHandler.named("cleanup-admin-command-replays", this::cleanupAdminCommandReplays),
                        BusinessHandler.named("cleanup-admin-sessions", this::cleanupAdminSessions),
                        BusinessHandler.named("cleanup-maintenance-runs", this::cleanupMaintenanceRuns),
                        BusinessHandler.named("cleanup-ocr-retry-commands", this::cleanupOcrRetryCommands),
                        BusinessHandler.named("claim-ocr-documents", this::claimOcrDocuments),
                        BusinessHandler.named("delete-ocr-documents", context -> {
                            for (OcrTask task : context.ocrTasks) {
                                try {
                                    storage.delete(task.getStorageKey());
                                    transactionTemplate.executeWithoutResult(status -> taskRepository
                                            .findByIdForUpdate(task.getId())
                                            .filter(current -> current.getStorageDeletedAt() == null)
                                            .ifPresent(current -> {
                                                current.markStorageDeleted(clock.instant());
                                                taskRepository.saveAndFlush(current);
                                                context.deletedOcrDocuments += 1;
                                            }));
                                } catch (RuntimeException exception) {
                                    storageDeleteFailures.increment();
                                    context.recordFailure("OCR_STORAGE_DELETE_FAILED");
                                    log.warn(
                                            "Could not delete retained OCR document: taskId={}",
                                            task.getPublicId(),
                                            exception);
                                }
                            }
                        }),
                        BusinessHandler.named("delete-ocr-orphans", this::deleteOcrOrphans),
                        BusinessHandler.named("build-result", context -> {
                            context.result = new MaintenanceResult(
                                    context.deletedSessions,
                                    context.deletedCommandReplays,
                                    context.deletedMaintenanceRuns,
                                    context.deletedOcrRetryCommands,
                                    context.deletedOcrDocuments,
                                    context.deletedOcrOrphans,
                                    context.failureCount,
                                    context.failureCount > 0,
                                    context.failureSummary());
                            context.run.complete(
                                    new MaintenanceRun.MaintenanceResultSnapshot(
                                            context.result.deletedSessions(),
                                            context.result.deletedCommandReplays(),
                                            context.result.deletedOcrRetryCommands(),
                                            context.result.deletedOcrDocuments(),
                                            context.result.deletedOcrOrphans(),
                                            context.result.failureCount(),
                                            context.result.partialFailure(),
                                            context.result.failureSummary()),
                                    clock.instant());
                            runRepository.saveAndFlush(context.run);
                        })),
                context -> context.result);
    }

    private void deleteOcrOrphans(MaintenanceContext context) {
        if (!storage.supportsListing()) {
            return;
        }
        List<OcrStorageObject> candidates;
        try {
            candidates = storage.listObjectsOlderThan(
                    clock.instant().minus(properties.ocrStorageOrphanGracePeriod()),
                    properties.ocrStorageOrphanScanLimit());
        } catch (RuntimeException exception) {
            orphanScanFailures.increment();
            context.recordFailure("OCR_STORAGE_ORPHAN_SCAN_FAILED");
            log.warn("Could not enumerate OCR storage for orphan cleanup", exception);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        Set<String> referenced = transactionTemplate.execute(status -> taskRepository
                .findReferencedStorageKeys(candidates.stream().map(OcrStorageObject::key).toList()));
        Set<String> referencedKeys = referenced == null ? Set.of() : referenced;
        for (OcrStorageObject candidate : candidates) {
            if (referencedKeys.contains(candidate.key())) {
                continue;
            }
            try {
                storage.delete(candidate.key());
                context.deletedOcrOrphans += 1;
                orphanDeleteCounter.increment();
            } catch (RuntimeException exception) {
                storageDeleteFailures.increment();
                context.recordFailure("OCR_ORPHAN_DELETE_FAILED");
                log.warn("Could not delete unreferenced OCR storage object: storageKey={}", candidate.key(), exception);
            }
        }
    }

    private void markRunFailed(MaintenanceContext context, RuntimeException exception) {
        if (context.run == null || context.run.getId() == null) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> runRepository.findById(context.run.getId())
                    .ifPresent(run -> {
                        // Keep database records stable and free of driver/path details; the full cause stays in logs.
                        run.fail(clock.instant(), "MAINTENANCE_HANDLER_FAILED:" + exception.getClass().getSimpleName());
                        runRepository.saveAndFlush(run);
                    }));
        } catch (RuntimeException markFailure) {
            log.error("Could not persist failed maintenance run: runId={}", context.run.getId(), markFailure);
        }
    }

    private void claimOcrDocuments(MaintenanceContext context) {
        context.ocrTasks = transactionTemplate.execute(status -> {
            List<OcrTask> tasks = taskRepository.findStorageCleanupCandidates(
                    TERMINAL_OCR_STATUSES,
                    clock.instant().minus(properties.ocrDocumentRetention()),
                    PageRequest.of(0, properties.batchSize() * properties.maxBatchesPerRun()));
            Instant claimedAt = clock.instant();
            tasks.forEach(task -> task.beginStorageCleanup(claimedAt));
            taskRepository.saveAllAndFlush(tasks);
            return List.copyOf(tasks);
        });
        if (context.ocrTasks == null) {
            context.ocrTasks = List.of();
        }
    }

    private void cleanupSessions(MaintenanceContext context) {
        java.time.Instant cutoff = clock.instant().minus(properties.sessionRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<String> ids = sessionRepository.findInactiveIdsBefore(
                        cutoff,
                        PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) {
                    return 0;
                }
                sessionRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            int count = deleted == null ? 0 : deleted;
            context.deletedSessions += count;
            if (count < properties.batchSize()) {
                return;
            }
        }
    }

    private void cleanupCommandReplays(MaintenanceContext context) {
        java.time.Instant cutoff = clock.instant().minus(properties.commandReplayRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<Long> ids = replayRepository.findIdsCreatedBefore(
                        cutoff,
                        PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) {
                    return 0;
                }
                replayRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            int count = deleted == null ? 0 : deleted;
            context.deletedCommandReplays += count;
            if (count < properties.batchSize()) {
                return;
            }
        }
    }

    private void cleanupAdminCommandReplays(MaintenanceContext context) {
        java.time.Instant cutoff = clock.instant().minus(properties.commandReplayRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<Long> ids = adminReplayRepository.findIdsCreatedBefore(
                        cutoff, PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) {
                    return 0;
                }
                adminReplayRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            if ((deleted == null ? 0 : deleted) < properties.batchSize()) {
                return;
            }
        }
    }

    private void cleanupAdminSessions(MaintenanceContext context) {
        java.time.Instant cutoff = clock.instant().minus(properties.sessionRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<String> ids = adminSessionRepository.findInactiveIdsBefore(
                        cutoff, PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) return 0;
                adminSessionRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            if ((deleted == null ? 0 : deleted) < properties.batchSize()) return;
        }
    }

    private void cleanupOcrRetryCommands(MaintenanceContext context) {
        java.time.Instant cutoff = clock.instant().minus(properties.ocrRetryCommandRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<Long> ids = retryCommandRepository.findIdsCreatedBefore(
                        cutoff,
                        PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) {
                    return 0;
                }
                retryCommandRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            int count = deleted == null ? 0 : deleted;
            context.deletedOcrRetryCommands += count;
            if (count < properties.batchSize()) {
                return;
            }
        }
    }

    private void cleanupMaintenanceRuns(MaintenanceContext context) {
        Instant cutoff = clock.instant().minus(properties.maintenanceRunRetention());
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status -> {
                List<Long> ids = runRepository.findIdsStartedBefore(
                        cutoff,
                        PageRequest.of(0, properties.batchSize()));
                if (ids.isEmpty()) {
                    return 0;
                }
                runRepository.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            int count = deleted == null ? 0 : deleted;
            context.deletedMaintenanceRuns += count;
            if (count < properties.batchSize()) {
                return;
            }
        }
    }

    public record MaintenanceResult(
            int deletedSessions,
            int deletedCommandReplays,
            int deletedMaintenanceRuns,
            int deletedOcrRetryCommands,
            int deletedOcrDocuments,
            int deletedOcrOrphans,
            int failureCount,
            boolean partialFailure,
            String failureSummary) {
    }

    private static final class MaintenanceContext extends BusinessContext {
        private final Instant startedAt;
        private MaintenanceRun run;
        private int deletedSessions;
        private int deletedCommandReplays;
        private int deletedMaintenanceRuns;
        private int deletedOcrRetryCommands;
        private List<OcrTask> ocrTasks = List.of();
        private int deletedOcrDocuments;
        private int deletedOcrOrphans;
        private int failureCount;
        private final List<String> failureMessages = new ArrayList<>();
        private MaintenanceResult result;

        private MaintenanceContext(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private void recordFailure(String code) {
            failureCount += 1;
            if (failureMessages.size() < 10 && !failureMessages.contains(code)) {
                failureMessages.add(code);
            }
        }

        private String failureSummary() {
            if (failureMessages.isEmpty()) {
                return null;
            }
            return String.join(",", failureMessages);
        }
    }
}
