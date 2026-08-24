package com.demeter.backend.ocr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ocr_retry_commands")
public class OcrRetryCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "task_version", nullable = false, updatable = false)
    private long taskVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OcrRetryCommand() {
    }

    public OcrRetryCommand(
            Long tenantId,
            Long taskId,
            String idempotencyKey,
            String requestHash,
            long taskVersion,
            Long createdBy,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.taskVersion = taskVersion;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
