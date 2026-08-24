package com.demeter.backend.ocr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ocr_tasks")
public class OcrTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    private String publicId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OcrTaskStatus status;

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    private String storageKey;

    @Column(name = "original_filename", length = 255, updatable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 80, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(length = 80)
    private String provider;

    @Column(name = "provider_request_id", length = 160)
    private String providerRequestId;

    @Column(name = "result_json", columnDefinition = "MEDIUMTEXT")
    private String resultJson;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 500)
    private String lastErrorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "storage_deleted_at")
    private Instant storageDeletedAt;

    @Column(name = "storage_cleanup_started_at")
    private Instant storageCleanupStartedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OcrTask() {
    }

    public OcrTask(
            Long tenantId,
            Long createdBy,
            String storageKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String contentSha256,
            String idempotencyKey,
            String requestHash,
            int maxAttempts,
            Instant now) {
        this.publicId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.createdBy = createdBy;
        this.status = OcrTaskStatus.PENDING;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.contentSha256 = contentSha256;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void claim(Instant now, Duration leaseDuration) {
        if (!isClaimable(now)) {
            throw new IllegalStateException("OCR task is not claimable");
        }
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("OCR task has exhausted its attempts");
        }
        status = OcrTaskStatus.PROCESSING;
        attemptCount += 1;
        startedAt = now;
        leaseUntil = now.plus(leaseDuration);
        lastErrorCode = null;
        lastErrorMessage = null;
        updatedAt = now;
    }

    public boolean isClaimable(Instant now) {
        boolean ready = (status == OcrTaskStatus.PENDING || status == OcrTaskStatus.RETRYING)
                && !nextAttemptAt.isAfter(now);
        boolean expiredLease = status == OcrTaskStatus.PROCESSING
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        return ready || expiredLease;
    }

    public boolean hasExhaustedAttempts() {
        return attemptCount >= maxAttempts;
    }

    public void succeed(OcrRecognitionResult result, String resultJson, Instant now) {
        status = OcrTaskStatus.SUCCEEDED;
        provider = result.provider();
        providerRequestId = result.providerRequestId();
        this.resultJson = resultJson;
        leaseUntil = null;
        completedAt = now;
        updatedAt = now;
    }

    public void fail(String errorCode, String errorMessage, Instant now, Duration retryDelay) {
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        leaseUntil = null;
        updatedAt = now;
        if (attemptCount < maxAttempts) {
            status = OcrTaskStatus.RETRYING;
            nextAttemptAt = now.plus(retryDelay);
        } else {
            status = OcrTaskStatus.FAILED;
            completedAt = now;
        }
    }

    public void failPermanently(String errorCode, String errorMessage, Instant now) {
        status = OcrTaskStatus.FAILED;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        leaseUntil = null;
        completedAt = now;
        updatedAt = now;
    }

    public void retry(Instant now) {
        if (status != OcrTaskStatus.FAILED) {
            throw new IllegalStateException("Only a failed OCR task can be retried");
        }
        if (storageCleanupStartedAt != null || storageDeletedAt != null) {
            throw new IllegalStateException("The OCR source image retention period has expired");
        }
        status = OcrTaskStatus.PENDING;
        attemptCount = 0;
        nextAttemptAt = now;
        leaseUntil = null;
        completedAt = null;
        startedAt = null;
        provider = null;
        providerRequestId = null;
        resultJson = null;
        lastErrorCode = null;
        lastErrorMessage = null;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public OcrTaskStatus getStatus() {
        return status;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getResultJson() {
        return resultJson;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getStorageDeletedAt() {
        return storageDeletedAt;
    }

    public Instant getStorageCleanupStartedAt() {
        return storageCleanupStartedAt;
    }

    public void beginStorageCleanup(Instant now) {
        if (status != OcrTaskStatus.SUCCEEDED && status != OcrTaskStatus.FAILED) {
            throw new IllegalStateException("Only a completed OCR task can enter storage cleanup");
        }
        if (storageCleanupStartedAt == null) {
            storageCleanupStartedAt = now;
            updatedAt = now;
        }
    }

    public void markStorageDeleted(Instant now) {
        if (storageCleanupStartedAt == null) {
            throw new IllegalStateException("OCR storage cleanup has not been claimed");
        }
        storageDeletedAt = now;
        updatedAt = now;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
