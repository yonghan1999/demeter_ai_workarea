package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnRuntimeRole({RuntimeRole.WORKER, RuntimeRole.MAINTENANCE})
public class OcrTaskMetrics {

    private static final List<OcrTaskStatus> READY_STATUSES = List.of(
            OcrTaskStatus.PENDING,
            OcrTaskStatus.RETRYING);

    private final OcrTaskRepository taskRepository;
    private final Clock clock;
    private final Map<OcrTaskStatus, AtomicLong> statusCounts = new EnumMap<>(OcrTaskStatus.class);
    private final Map<WorkOutcome, Counter> outcomes = new EnumMap<>(WorkOutcome.class);
    private final Map<FailureCategory, Counter> failures = new EnumMap<>(FailureCategory.class);
    private final AtomicLong oldestReadyAgeSeconds = new AtomicLong();

    public OcrTaskMetrics(OcrTaskRepository taskRepository, MeterRegistry meterRegistry, Clock clock) {
        this.taskRepository = taskRepository;
        this.clock = clock;
        for (OcrTaskStatus status : OcrTaskStatus.values()) {
            AtomicLong count = new AtomicLong();
            statusCounts.put(status, count);
            Gauge.builder("demeter.ocr.tasks", count, AtomicLong::get)
                    .description("Current OCR task count by status")
                    .tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                    .register(meterRegistry);
        }
        Gauge.builder("demeter.ocr.queue.oldest.ready.age.seconds", oldestReadyAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest currently claimable OCR task")
                .register(meterRegistry);
        for (WorkOutcome outcome : WorkOutcome.values()) {
            outcomes.put(outcome, Counter.builder("demeter.ocr.worker.tasks")
                    .description("OCR worker task outcomes")
                    .tag("outcome", outcome.metricTag())
                    .register(meterRegistry));
        }
        for (FailureCategory category : FailureCategory.values()) {
            failures.put(category, Counter.builder("demeter.ocr.worker.failures")
                    .description("OCR worker failures by stable category")
                    .tag("category", category.metricTag())
                    .register(meterRegistry));
        }
    }

    @Scheduled(
            fixedDelayString = "${demeter.ocr.metrics.fixed-delay:15s}",
            initialDelayString = "${demeter.ocr.metrics.initial-delay:5s}")
    @Transactional(readOnly = true)
    public void refreshQueueGauges() {
        statusCounts.values().forEach(count -> count.set(0));
        taskRepository.countTasksByStatus().forEach(row ->
                statusCounts.get(row.status()).set(row.taskCount()));

        Instant now = clock.instant();
        long ageSeconds = taskRepository.findOldestClaimableCreatedAt(
                        READY_STATUSES,
                        OcrTaskStatus.PROCESSING,
                        now)
                .map(createdAt -> Math.max(0, Duration.between(createdAt, now).toSeconds()))
                .orElse(0L);
        oldestReadyAgeSeconds.set(ageSeconds);
    }

    public void recordOutcome(WorkOutcome outcome) {
        outcomes.get(outcome).increment();
    }

    public void recordFailure(FailureCategory category) {
        failures.get(category).increment();
    }

    public enum WorkOutcome {
        SUCCEEDED("succeeded"),
        RETRY_SCHEDULED("retry_scheduled"),
        FAILED("failed"),
        ATTEMPTS_EXHAUSTED("attempts_exhausted"),
        LEASE_LOST("lease_lost");

        private final String metricTag;

        WorkOutcome(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    public enum FailureCategory {
        DOCUMENT_UNAVAILABLE("document_unavailable"),
        NOT_CONFIGURED("not_configured"),
        PROVIDER_RETRYABLE("provider_retryable"),
        PROVIDER_INVALID_INPUT("provider_invalid_input"),
        PROVIDER_QUOTA_EXCEEDED("provider_quota_exceeded"),
        PROVIDER_PERMANENT("provider_permanent"),
        EMPTY_RESPONSE("empty_response"),
        INVALID_RESPONSE("invalid_response"),
        INTERNAL_ERROR("internal_error"),
        ATTEMPTS_EXHAUSTED("attempts_exhausted");

        private final String metricTag;

        FailureCategory(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }
}
