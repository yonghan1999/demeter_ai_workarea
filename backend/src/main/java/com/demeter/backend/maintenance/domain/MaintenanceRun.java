package com.demeter.backend.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "maintenance_runs")
public class MaintenanceRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_type", nullable = false, length = 64, updatable = false)
    private String runType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MaintenanceRunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "deleted_sessions", nullable = false)
    private int deletedSessions;

    @Column(name = "deleted_command_replays", nullable = false)
    private int deletedCommandReplays;

    @Column(name = "deleted_ocr_retry_commands", nullable = false)
    private int deletedOcrRetryCommands;

    @Column(name = "deleted_ocr_documents", nullable = false)
    private int deletedOcrDocuments;

    @Column(name = "deleted_ocr_orphans", nullable = false)
    private int deletedOcrOrphans;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "failure_summary", length = 1000)
    private String failureSummary;

    protected MaintenanceRun() {
    }

    public MaintenanceRun(String runType, Instant startedAt) {
        this.runType = runType;
        this.status = MaintenanceRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public void complete(MaintenanceResultSnapshot result, Instant completedAt) {
        if (status != MaintenanceRunStatus.RUNNING) {
            throw new IllegalStateException("Maintenance run is already complete");
        }
        this.status = result.partialFailure()
                ? MaintenanceRunStatus.PARTIAL_FAILURE
                : MaintenanceRunStatus.SUCCEEDED;
        this.completedAt = completedAt;
        this.durationMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        this.deletedSessions = result.deletedSessions();
        this.deletedCommandReplays = result.deletedCommandReplays();
        this.deletedOcrRetryCommands = result.deletedOcrRetryCommands();
        this.deletedOcrDocuments = result.deletedOcrDocuments();
        this.deletedOcrOrphans = result.deletedOcrOrphans();
        this.failureCount = result.failureCount();
        this.failureSummary = result.failureSummary();
    }

    public void fail(Instant completedAt, String summary) {
        if (status != MaintenanceRunStatus.RUNNING) {
            return;
        }
        this.status = MaintenanceRunStatus.FAILED;
        this.completedAt = completedAt;
        this.durationMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        this.failureCount = Math.max(1, failureCount);
        this.failureSummary = truncate(summary);
    }

    public Long getId() {
        return id;
    }

    public MaintenanceRunStatus getStatus() {
        return status;
    }

    public String getRunType() {
        return runType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public int getDeletedSessions() { return deletedSessions; }
    public int getDeletedCommandReplays() { return deletedCommandReplays; }
    public int getDeletedOcrRetryCommands() { return deletedOcrRetryCommands; }
    public int getDeletedOcrDocuments() { return deletedOcrDocuments; }
    public int getDeletedOcrOrphans() { return deletedOcrOrphans; }
    public int getFailureCount() { return failureCount; }
    public String getFailureSummary() { return failureSummary; }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record MaintenanceResultSnapshot(
            int deletedSessions,
            int deletedCommandReplays,
            int deletedOcrRetryCommands,
            int deletedOcrDocuments,
            int deletedOcrOrphans,
            int failureCount,
            boolean partialFailure,
            String failureSummary) {
    }
}
