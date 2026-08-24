package com.demeter.backend.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private Long billId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private PaymentMethod method;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "reference_no", length = 120, updatable = false)
    private String referenceNo;

    @Column(length = 240, updatable = false)
    private String note;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by")
    private Long reversedBy;

    @Column(name = "reversal_reason", length = 240)
    private String reversalReason;

    @Column(name = "reversal_idempotency_key", length = 128)
    private String reversalIdempotencyKey;

    @Column(name = "reversal_request_hash", length = 64)
    private String reversalRequestHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(
            Long tenantId,
            Long billId,
            BigDecimal amount,
            PaymentMethod method,
            Instant paidAt,
            String referenceNo,
            String note,
            String idempotencyKey,
            String requestHash,
            Long createdBy,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.billId = billId;
        this.amount = amount;
        this.method = method;
        this.paidAt = paidAt;
        this.referenceNo = referenceNo;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = PaymentStatus.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void reverse(
            Long actorUserId,
            String reason,
            String idempotencyKey,
            String requestHash,
            Instant now) {
        if (status != PaymentStatus.ACTIVE) {
            throw new IllegalStateException("Only an active payment can be reversed");
        }
        this.status = PaymentStatus.REVERSED;
        this.reversedBy = actorUserId;
        this.reversalReason = reason;
        this.reversalIdempotencyKey = idempotencyKey;
        this.reversalRequestHash = requestHash;
        this.reversedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getBillId() {
        return billId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public String getNote() {
        return note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public Long getReversedBy() {
        return reversedBy;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public String getReversalIdempotencyKey() {
        return reversalIdempotencyKey;
    }

    public String getReversalRequestHash() {
        return reversalRequestHash;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
