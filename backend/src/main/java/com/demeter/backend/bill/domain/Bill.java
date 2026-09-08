package com.demeter.backend.bill.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "bills")
@DynamicUpdate
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String shipper;

    @Column(name = "shipper_normalized", nullable = false, length = 120)
    private String shipperNormalized;

    @Column(name = "vehicle_cargo", length = 120)
    private String vehicleCargo;

    @Column(name = "transport_date", nullable = false)
    private LocalDate date;

    @Column(name = "origin", nullable = false, length = 64)
    private String origin;

    @Column(name = "destination", nullable = false, length = 64)
    private String destination;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BillStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "creation_idempotency_key", length = 128, updatable = false)
    private String creationIdempotencyKey;

    @Column(name = "creation_request_hash", length = 64, updatable = false)
    private String creationRequestHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "delete_reason", length = 240)
    private String deleteReason;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "bill_tags", joinColumns = @JoinColumn(name = "bill_id"))
    @Column(name = "tag", nullable = false, length = 40)
    @BatchSize(size = 100)
    private Set<String> tags = new LinkedHashSet<>();

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Bill() {
    }

    public Bill(
            Long tenantId,
            Long actorUserId,
            String code,
            String shipper,
            String shipperNormalized,
            String vehicleCargo,
            LocalDate date,
            String origin,
            String destination,
            BigDecimal amount,
            LocalDate dueDate,
            Set<String> tags,
            String creationIdempotencyKey,
            String creationRequestHash,
            Instant now) {
        this.tenantId = tenantId;
        this.createdBy = actorUserId;
        this.updatedBy = actorUserId;
        this.code = code;
        this.creationIdempotencyKey = creationIdempotencyKey;
        this.creationRequestHash = creationRequestHash;
        this.paidAmount = BigDecimal.ZERO.setScale(2);
        apply(shipper, shipperNormalized, vehicleCargo, date, origin, destination, amount, dueDate, tags);
        this.status = BillStatus.UNPAID;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(
            Long actorUserId,
            String shipper,
            String shipperNormalized,
            String vehicleCargo,
            LocalDate date,
            String origin,
            String destination,
            BigDecimal amount,
            LocalDate dueDate,
            Set<String> tags,
            Instant now) {
        if (amount.compareTo(paidAmount) < 0) {
            throw new IllegalArgumentException("Bill amount cannot be less than the paid amount");
        }
        apply(shipper, shipperNormalized, vehicleCargo, date, origin, destination, amount, dueDate, tags);
        refreshStatus();
        this.updatedBy = actorUserId;
        this.updatedAt = now;
    }

    public void registerPayment(BigDecimal paymentAmount, Long actorUserId, Instant now) {
        if (paymentAmount == null || paymentAmount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        BigDecimal updated = paidAmount.add(paymentAmount);
        if (updated.compareTo(amount) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds the outstanding amount");
        }
        paidAmount = updated;
        this.updatedBy = actorUserId;
        this.updatedAt = now;
        refreshStatus();
    }

    public void reversePayment(BigDecimal paymentAmount, Long actorUserId, Instant now) {
        if (paymentAmount == null || paymentAmount.signum() <= 0 || paidAmount.compareTo(paymentAmount) < 0) {
            throw new IllegalArgumentException("Payment reversal amount is invalid");
        }
        paidAmount = paidAmount.subtract(paymentAmount);
        this.updatedBy = actorUserId;
        this.updatedAt = now;
        refreshStatus();
    }

    public void reversePaymentBySystem(BigDecimal paymentAmount, Instant now) {
        if (paymentAmount == null || paymentAmount.signum() <= 0 || paidAmount.compareTo(paymentAmount) < 0) {
            throw new IllegalArgumentException("Payment reversal amount is invalid");
        }
        paidAmount = paidAmount.subtract(paymentAmount);
        this.updatedAt = now;
        refreshStatus();
    }

    public void softDelete(Long actorUserId, String reason, Instant now) {
        if (deletedAt != null) {
            throw new IllegalStateException("Bill has already been deleted");
        }
        this.deletedBy = actorUserId;
        this.deleteReason = reason;
        this.deletedAt = now;
        this.updatedBy = actorUserId;
        this.updatedAt = now;
    }

    public void softDeleteBySystem(String reason, Instant now) {
        if (deletedAt != null) {
            throw new IllegalStateException("Bill has already been deleted");
        }
        this.deletedBy = null;
        this.deleteReason = reason;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public void restore(Long actorUserId, Instant now) {
        if (deletedAt == null) {
            throw new IllegalStateException("Only a deleted bill can be restored");
        }
        this.deletedBy = null;
        this.deleteReason = null;
        this.deletedAt = null;
        this.updatedBy = actorUserId;
        this.updatedAt = now;
    }

    public void restoreBySystem(Instant now) {
        if (deletedAt == null) {
            throw new IllegalStateException("Only a deleted bill can be restored");
        }
        this.deletedBy = null;
        this.deleteReason = null;
        this.deletedAt = null;
        this.updatedAt = now;
    }

    private void apply(
            String shipper,
            String shipperNormalized,
            String vehicleCargo,
            LocalDate date,
            String origin,
            String destination,
            BigDecimal amount,
            LocalDate dueDate,
            Set<String> tags) {
        this.shipper = shipper;
        this.shipperNormalized = shipperNormalized;
        this.vehicleCargo = vehicleCargo;
        this.date = date;
        this.origin = origin;
        this.destination = destination;
        this.amount = amount;
        this.dueDate = dueDate;
        this.tags.clear();
        this.tags.addAll(tags);
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getShipper() {
        return shipper;
    }

    public String getShipperNormalized() {
        return shipperNormalized;
    }

    public String getVehicleCargo() {
        return vehicleCargo;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getOutstandingAmount() {
        return amount.subtract(paidAmount);
    }

    public BillStatus getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getCreationIdempotencyKey() {
        return creationIdempotencyKey;
    }

    public String getCreationRequestHash() {
        return creationRequestHash;
    }

    public Set<String> getTags() {
        return Set.copyOf(tags);
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    private void refreshStatus() {
        if (paidAmount.signum() == 0) {
            status = BillStatus.UNPAID;
        } else if (paidAmount.compareTo(amount) < 0) {
            status = BillStatus.PARTIALLY_PAID;
        } else {
            status = BillStatus.PAID;
        }
    }
}
