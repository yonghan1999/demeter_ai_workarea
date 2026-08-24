package com.demeter.backend.ocr.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.ocr.domain.OcrBillCandidate;
import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import com.demeter.backend.ocr.spi.OcrProviderFailure;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "demeter.ocr.worker.enabled=true",
        "demeter.ocr.worker.fixed-delay=1h",
        "demeter.ocr.worker.initial-delay=1h"
})
@ActiveProfiles("test")
@Import(OcrTaskWorkerIntegrationTest.FakeOcrConfiguration.class)
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql",
        "classpath:db/testdata/ocr-pending.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OcrTaskWorkerIntegrationTest {

    @Autowired
    OcrTaskWorker worker;

    @Autowired
    OcrTaskRepository taskRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    FakeOcrProvider fakeOcrProvider;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    com.demeter.backend.ocr.infrastructure.OcrTaskMetrics taskMetrics;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetProvider() {
        fakeOcrProvider.reset();
        circuitBreakerRegistry.circuitBreaker("ocrProvider").reset();
    }

    @Test
    void processesAPersistedTaskThroughTheProviderAndStoresTheResult() {
        worker.poll();

        var task = taskRepository.findById(1501L).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(OcrTaskStatus.SUCCEEDED);
        assertThat(task.getProvider()).isEqualTo("fake-ocr");
        assertThat(task.getProviderRequestId()).isEqualTo("fake-request-001");
        assertThat(task.getResultJson()).contains("张三物流有限公司");
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLeaseUntil()).isNull();
        assertThat(fakeOcrProvider.lastRequest.get().taskId())
                .isEqualTo("00000000-0000-0000-0000-000000001501");
        assertThat(fakeOcrProvider.lastRequest.get().attempt()).isEqualTo(1);
    }

    @Test
    void schedulesATransientProviderFailureForRetry() {
        fakeOcrProvider.mode = ProviderMode.FAIL;

        worker.poll();

        var task = taskRepository.findById(1501L).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(OcrTaskStatus.RETRYING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorCode()).isEqualTo("OCR_PROVIDER_RETRYABLE");
        assertThat(task.getLeaseUntil()).isNull();
    }

    @Test
    void permanentlyClassifiesProviderInputQuotaAndPermanentFailures() {
        assertProviderFailure(
                ProviderMode.INVALID_INPUT,
                "OCR_PROVIDER_INVALID_INPUT",
                "provider_invalid_input");
        resetTaskForNextFailure();
        assertProviderFailure(
                ProviderMode.QUOTA_EXCEEDED,
                "OCR_PROVIDER_QUOTA_EXCEEDED",
                "provider_quota_exceeded");
        resetTaskForNextFailure();
        assertProviderFailure(
                ProviderMode.PERMANENT,
                "OCR_PROVIDER_PERMANENT",
                "provider_permanent");
    }

    @Test
    void permanentlyFailsAnInvalidProviderResponse() {
        fakeOcrProvider.mode = ProviderMode.INVALID;

        worker.poll();

        var task = taskRepository.findById(1501L).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(OcrTaskStatus.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("OCR_INVALID_RESPONSE");
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void closesAnExpiredLeaseAfterTheMaximumAttemptCount() {
        Instant expired = Instant.now().minusSeconds(60);
        Instant started = expired.minusSeconds(60);
        Instant created = started.minusSeconds(60);
        jdbcTemplate.update(
                """
                UPDATE ocr_tasks
                SET status = 'PROCESSING', attempt_count = max_attempts,
                    lease_until = ?, started_at = ?, completed_at = NULL,
                    created_at = ?
                WHERE id = 1501
                """,
                Timestamp.from(expired),
                Timestamp.from(started),
                Timestamp.from(created));

        worker.poll();

        var task = taskRepository.findById(1501L).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(OcrTaskStatus.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("OCR_ATTEMPTS_EXHAUSTED");
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(fakeOcrProvider.lastRequest.get()).isNull();
    }

    @Test
    void skipsALockedTaskAndClaimsTheNextTask() throws Exception {
        insertPendingTask(1502L, "00000000-0000-0000-0000-000000001502");
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    taskRepository.findByIdForUpdate(1501L).orElseThrow();
                    rowLocked.countDown();
                    await(releaseLock);
                }));

        try {
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            worker.poll();

            assertThat(taskRepository.findById(1501L).orElseThrow().getStatus())
                    .isEqualTo(OcrTaskStatus.PENDING);
            assertThat(taskRepository.findById(1502L).orElseThrow().getStatus())
                    .isEqualTo(OcrTaskStatus.SUCCEEDED);
            assertThat(fakeOcrProvider.lastRequest.get().taskId())
                    .isEqualTo("00000000-0000-0000-0000-000000001502");
        } finally {
            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void publishesQueueAgeStatusAndWorkerOutcomeMetrics() {
        Instant twoMinutesAgo = Instant.now().minus(Duration.ofMinutes(2));
        jdbcTemplate.update(
                "UPDATE ocr_tasks SET created_at = ?, updated_at = ? WHERE id = 1501",
                Timestamp.from(twoMinutesAgo),
                Timestamp.from(twoMinutesAgo));

        taskMetrics.refreshQueueGauges();

        assertThat(meterRegistry.get("demeter.ocr.tasks")
                        .tag("status", "pending")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("demeter.ocr.queue.oldest.ready.age.seconds")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(119.0);

        double succeededBefore = meterRegistry.get("demeter.ocr.worker.tasks")
                .tag("outcome", "succeeded")
                .counter()
                .count();
        worker.poll();

        assertThat(meterRegistry.get("demeter.ocr.worker.tasks")
                        .tag("outcome", "succeeded")
                        .counter()
                        .count())
                .isEqualTo(succeededBefore + 1.0);
    }

    private void assertProviderFailure(ProviderMode mode, String expectedCode, String metricCategory) {
        double failuresBefore = meterRegistry.get("demeter.ocr.worker.failures")
                .tag("category", metricCategory)
                .counter()
                .count();
        fakeOcrProvider.mode = mode;

        worker.poll();

        var task = taskRepository.findById(1501L).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(OcrTaskStatus.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo(expectedCode);
        assertThat(task.getLastErrorMessage()).doesNotContain("vendor-sensitive-detail");
        assertThat(meterRegistry.get("demeter.ocr.worker.failures")
                        .tag("category", metricCategory)
                        .counter()
                        .count())
                .isEqualTo(failuresBefore + 1.0);
    }

    private void resetTaskForNextFailure() {
        jdbcTemplate.update(
                """
                UPDATE ocr_tasks
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                    lease_until = NULL, last_error_code = NULL, last_error_message = NULL,
                    started_at = NULL, completed_at = NULL, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1501
                """);
        fakeOcrProvider.reset();
        circuitBreakerRegistry.circuitBreaker("ocrProvider").reset();
    }

    private void insertPendingTask(long id, String publicId) {
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    id, public_id, tenant_id, created_by, status, storage_key, original_filename,
                    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
                    provider, provider_request_id, result_json, attempt_count, max_attempts,
                    next_attempt_at, lease_until, last_error_code, last_error_message, started_at,
                    completed_at, version, created_at, updated_at)
                VALUES (?, ?, 1001, 1101, 'PENDING', ?, 'worker-fixture.jpg', 'image/jpeg', 4,
                    '32461d5bd1773012ac9f3f84abf7e2300c47b1677e7d8277a273ed9c2c89e6f3',
                    ?, ?, NULL, NULL, NULL, 0, 3, CURRENT_TIMESTAMP, NULL, NULL, NULL, NULL, NULL,
                    0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                publicId,
                "1001/worker-fixture-" + id + ".jpg",
                "worker-fixture-" + id,
                String.format("%064d", id));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the database row lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding a database row lock", exception);
        }
    }

    enum ProviderMode {
        SUCCESS,
        FAIL,
        INVALID,
        INVALID_INPUT,
        QUOTA_EXCEEDED,
        PERMANENT
    }

    static class FakeOcrProvider implements HandwrittenBillOcrProvider {

        private final AtomicReference<OcrRecognitionRequest> lastRequest = new AtomicReference<>();
        private volatile ProviderMode mode = ProviderMode.SUCCESS;

        @Override
        public OcrRecognitionResult recognize(OcrRecognitionRequest request) {
            lastRequest.set(request);
            if (mode == ProviderMode.FAIL) {
                throw new IllegalStateException("simulated provider failure");
            }
            if (mode == ProviderMode.INVALID) {
                return new OcrRecognitionResult("", null, List.of());
            }
            if (mode == ProviderMode.INVALID_INPUT) {
                throw new OcrProviderFailure(
                        OcrProviderFailure.Kind.INVALID_INPUT,
                        "vendor-sensitive-detail");
            }
            if (mode == ProviderMode.QUOTA_EXCEEDED) {
                throw new OcrProviderFailure(
                        OcrProviderFailure.Kind.QUOTA_EXCEEDED,
                        "vendor-sensitive-detail");
            }
            if (mode == ProviderMode.PERMANENT) {
                throw new OcrProviderFailure(
                        OcrProviderFailure.Kind.PERMANENT,
                        "vendor-sensitive-detail");
            }
            return new OcrRecognitionResult(
                    "fake-ocr",
                    "fake-request-001",
                    List.of(new OcrBillCandidate(
                            "candidate-001",
                            null,
                            "张三物流有限公司",
                            "9.6米高栏 / 煤炭",
                            LocalDate.of(2024, 5, 20),
                            "上海",
                            "北京",
                            new BigDecimal("4500.00"),
                            null,
                            new BigDecimal("0.98"),
                            Map.of("shipper", new BigDecimal("0.99")))));
        }

        void reset() {
            mode = ProviderMode.SUCCESS;
            lastRequest.set(null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeOcrConfiguration {

        @Bean
        @Primary
        FakeOcrProvider fakeOcrProvider() {
            return new FakeOcrProvider();
        }

        @Bean
        @Primary
        OcrDocumentStorage fakeOcrDocumentStorage() {
            return new OcrDocumentStorage() {
                @Override
                public String store(long tenantId, String objectName, OcrDocument document) {
                    return tenantId + "/" + objectName;
                }

                @Override
                public OcrDocument load(String storageKey, String originalFilename, String contentType) {
                    return new OcrDocument(
                            originalFilename,
                            contentType,
                            new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
                }

                @Override
                public void delete(String storageKey) {
                }
            };
        }
    }
}
